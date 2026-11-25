package com.xiaozhi.ai.llm.factory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xiaozhi.common.model.bo.ConfigBO;
import com.xiaozhi.common.model.bo.RoleBO;
import com.xiaozhi.agent.AgentProviders;
import com.xiaozhi.config.service.ConfigService;
import com.xiaozhi.event.AiConfigChangedEvent;
import com.xiaozhi.event.LlmModelFallbackEvent;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
/**
 * ChatModel
 * 
 * 设计模式: 策略模式 + 工厂模式
 * - 通过ChatModelProvider接口定义统一的创建策略
 * - 每个LLM提供商实现独立的Provider
 * - 工厂类通过Spring自动注入所有Provider,自动路由到对应实现
 */
@Slf4j
@Component
public class ChatModelFactory {
    
    @Autowired
    private ConfigService configService;

    /**
     * 降级事件发布口。required=false：单元测试里直接 new 出来的工厂没有 Spring 容器，
     * 发布口为空时只记日志、不影响主流程。
     */
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    
    /**
     * 所有的ChatModel提供者,Spring会自动注入所有实现了ChatModelProvider接口的Bean
     */
    private final Map<String, ChatModelProvider> providers;

    /**
     * key 见 cacheKey()，配置或角色参数变更时失效。
     * temperature/topP 是角色自由填写的数值，组合数没有上限，用 maximumSize 兜底防止无限增长；
     * expireAfterAccess(24h) 是 Redis 广播丢失时的兜底——僵尸配置最迟 24h 强制重建；
     * 容量淘汰只从 Map 里摘除引用交给 GC，不会主动 close，避免误伤正在使用该实例的会话。
     */
    private final Cache<String, ChatModel> chatModelCache = Caffeine.newBuilder()
            .maximumSize(256)
            .expireAfterAccess(Duration.ofHours(24))
            .build();

    /** key 为 configId，配置变更时失效；与主缓存同技术栈：容量 + TTL 兜底 */
    private final Cache<Integer, EmbeddingModel> embeddingModelCache = Caffeine.newBuilder()
            .maximumSize(64)
            .expireAfterAccess(Duration.ofHours(24))
            .build();

    /**
     * 每个角色上次降级的目标配置 ID：目标没变时降级为 debug 级别、不再重复发事件。
     * 配置停用期间设备每说一句话就触发一次降级，不去重一天能淹几万条告警。
     */
    private final Map<Integer, Integer> lastFallbackTarget = new ConcurrentHashMap<>();

    @Autowired
    private ObservationRegistry registry;
    /**
     * 构造函数,自动注入所有ChatModelProvider
     * @param providers 所有的Provider实现
     */
    @Autowired
    public ChatModelFactory(List<ChatModelProvider> providers) {
        // 将Provider列表转换为Map,key为provider名称(小写),value为Provider实例
        this.providers = providers.stream()
                .collect(Collectors.toMap(
                        p -> p.getProviderName().toLowerCase(),
                        Function.identity()
                ));
    }
    
    public ChatModel getChatModel(RoleBO role) {
        RoleBO effectiveRole = role != null ? role : new RoleBO();
        ConfigBO config = resolveConfig(effectiveRole);
        // 每轮重建会连带新建 HttpClient，等于每次对话都重新做一次 TCP+TLS 握手。
        // temperature/topP 烘焙在模型的 defaultOptions 里，必须进 key，否则改了角色参数不生效；
        // key 按实际用的配置算，回退出来的默认模型不能挂在已停用配置的名下
        String cacheKey = cacheKey(config.getConfigId(), effectiveRole);
        return chatModelCache.get(cacheKey, k -> createChatModel(config, effectiveRole));
    }

    /**
     * 角色这一轮实际要用的模型配置。绑定的配置已停用或不存在时回退默认聊天模型，与 TTS 停用时的处理一致；
     * 不能照搬 STT 静默返回空的做法——模型建不起来，这一轮就彻底没法回答。连默认都没有才报错。
     */
    ConfigBO resolveConfig(RoleBO role) {
        Integer modelId = role.getModelId();
        // 角色没绑模型（modelId=null）和绑了不存在/已停用的配置，都是"这一轮没有可用模型"，
        // 走同一条降级路径。之前对 null 直接 Assert 抛错，等于最容易发生的场景反而没有兜底。
        ConfigBO config = modelId == null ? null : configService.getConfig(modelId);
        if (config != null && !ConfigBO.STATE_DISABLED.equals(config.getState())) {
            if (role.getRoleId() != null) {
                // 恢复正常：清除该角色的降级记忆，下次再降级要重新告警
                lastFallbackTarget.remove(role.getRoleId());
            }
            return config;
        }
        // 三条原因分开记，排障时才能一眼看出是"没绑"还是"绑了但坏了"
        String reason;
        if (modelId == null) {
            reason = "角色未绑定模型";
        } else if (config == null) {
            reason = "绑定的模型配置不存在（modelId=" + modelId + "）";
        } else {
            reason = "绑定的模型配置已停用（modelId=" + modelId + "）";
        }
        ConfigBO fallback = configService.getDefaultConfig("llm", ConfigBO.ModelType.chat.getValue());
        if (fallback == null) {
            // 状态不可用，不是调用方参数错：用 IllegalStateException
            throw new IllegalStateException("角色「" + role.getRoleName() + "」" + reason
                    + "，且没有可用的默认对话模型；请在配置管理里启用该配置或给角色改绑模型");
        }
        // 匿名角色（roleId=null）没有稳定身份，不做去重，每次降级都告警
        boolean targetChanged = true;
        if (role.getRoleId() != null) {
            Integer last = lastFallbackTarget.put(role.getRoleId(), fallback.getConfigId());
            targetChanged = last == null || !last.equals(fallback.getConfigId());
        }
        if (targetChanged) {
            log.warn("LLM 模型降级 - 角色: {}, 原因: {}, fromConfigId: {}, toConfigId: {}, provider: {}",
                    role.getRoleName(), reason, modelId, fallback.getConfigId(), fallback.getProvider());
            publishFallbackEvent(role, modelId, fallback, reason);
        } else {
            log.debug("LLM 模型仍处于降级状态，跳过重复告警 - 角色: {}, toConfigId: {}",
                    role.getRoleName(), fallback.getConfigId());
        }
        return fallback;
    }

    /**
     * 把降级事实抛给监控/后台。发布口为空（单测直接 new）或监听方抛异常都不影响本轮对话——
     * 降级的兜底目的就是让对话继续，不能因为"通知失败"再炸一次。
     */
    private void publishFallbackEvent(RoleBO role, Integer fromConfigId, ConfigBO fallback, String reason) {
        if (eventPublisher == null) {
            return;
        }
        try {
            eventPublisher.publishEvent(new LlmModelFallbackEvent(role.getRoleName(), role.getRoleId(),
                    fromConfigId, fallback.getConfigId(), fallback.getProvider(), reason));
        } catch (RuntimeException e) {
            log.warn("发布模型降级事件失败（不影响本轮对话） - 角色: {}", role.getRoleName(), e);
        }
    }

    static String cacheKey(Integer modelId, RoleBO role) {
        return modelId + ":" + role.getTemperature() + ":" + role.getTopP();
    }

    /**
     * 本进程内的配置变更：HTTP 入口所在进程不订阅 Redis 广播（xiaozhi-dialogue 不在它的运行时依赖里），
     * 只能靠本地事件失效缓存；对话进程那侧由 RedisSubscriber.onConfigChanged 处理跨实例广播。
     * 提交后才清，避免回滚的事务把缓存白清一遍。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAiConfigChanged(AiConfigChangedEvent event) {
        if ("llm".equals(event.getConfigType())) {
            removeCache(event.getConfigId());
        } else if (AgentProviders.CREDENTIAL_CONFIG_TYPE.equals(event.getConfigType())) {
            removeAgentModels(event.getConfigId());
        }
    }

    /** 平台凭据改了、停用或删了：挂在它下面的智能体模型还拿着改前的凭据，都要重建 */
    private void removeAgentModels(Integer credentialId) {
        ConfigBO credential = configService.getBO(credentialId);
        if (credential == null) {
            return;
        }
        for (ConfigBO model : configService.listAgentModels(credential)) {
            removeCache(model.getConfigId());
        }
    }

    /**
     * 配置变更后必须失效：configId 不变但 apiKey/endpoint/模型名可能已经改了
     */
    public void removeCache(Integer configId) {
        if (configId == null) {
            return;
        }
        // 只摘引用、不 close：实例可能正被 Persona/网页聊天/摘要器持有并在途使用，主动 close 会误伤；
        // 与容量淘汰策略统一（见 chatModelCache 字段注释），资源交给 GC 与连接池空闲回收。
        chatModelCache.asMap().entrySet().removeIf(entry -> entry.getKey().startsWith(configId + ":"));
        embeddingModelCache.invalidate(configId);
    }

    public ChatModel getVisionModel() {
        ConfigBO config = configService.getDefaultConfig("llm", ConfigBO.ModelType.vision.getValue());
        if (config == null) {
            throw new IllegalStateException("未配置多模态模型：请在配置管理里新增一条 llm/vision 类型的配置");
        }
        // 走同一份缓存，避免每次多模态调用都新建 ChatModel(连带新建 HttpClient)
        String cacheKey = config.getConfigId() + ":vision";
        return chatModelCache.get(cacheKey, k -> createChatModel(config, new RoleBO()));
    }

    public EmbeddingModel getEmbeddingModel(Integer configId) {
        if (configId == null) {
            // 调用方传了空 ID：参数错误
            throw new IllegalArgumentException("configId 不能为空");
        }
        return embeddingModelCache.get(configId, id -> {
            ConfigBO config = configService.getConfig(id);
            if (config == null) {
                // 配置查不到：状态不可用
                throw new IllegalStateException("未找到模型配置, configId=" + id);
            }
            return getEmbeddingModel(config);
        });
    }

    public EmbeddingModel getEmbeddingModel(ConfigBO config) {
        if (config == null) {
            throw new IllegalStateException("未配置向量模型：请在配置管理里新增一条 llm/embedding 类型的配置");
        }
        String providerName = config.getProvider().toLowerCase();
        ChatModelProvider provider = providers.get(providerName);
        if (provider != null) {
            return provider.createEmbeddingModel(config);
        }
        provider = providers.get("openai");
        if (provider != null) {
            return provider.createEmbeddingModel(config);
        }
        throw new IllegalStateException(
                String.format("不支持的 Provider: %s, 可用的 Providers: %s", providerName, providers.keySet()));
    }

    /**
     * 创建ChatModel
     *
     * @param config 模型配置
     * @param role 角色配置
     * @return ChatModel实例
     */
    public ChatModel createChatModel(ConfigBO config, RoleBO role) {
        String providerName = config.getProvider().toLowerCase();

        ChatModelProvider provider = providers.get(providerName);
        if (provider == null) {
            // 没有对应的 Provider 时回退到 OpenAI(兼容 OpenAI 协议)
            provider = providers.get("openai");
        }
        if (provider == null) {
            throw new IllegalStateException(
                    String.format("不支持的 Provider: %s, 可用的 Providers: %s",
                            providerName,
                            providers.keySet())
            );
        }
        return provider.createChatModel(config, role);
    }
}
