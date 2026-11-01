package com.xiaozhi.ai.rag;

import com.xiaozhi.ai.llm.factory.ChatModelFactory;
import com.xiaozhi.common.model.bo.ConfigBO;
import com.xiaozhi.common.port.ConfigLookup;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;

import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * RAG 向量库服务（Qdrant）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>全部懒加载</b>：Qdrant 连不上、或 embedding 配置还没建，都<b>不影响应用启动</b>，
 *       只有真正用到 RAG 时才报错并返回可读的原因。</li>
 *   <li><b>复用项目已有工厂</b>：EmbeddingModel 走 {@link ChatModelFactory#getEmbeddingModel(ConfigBO)}，
 *       与 ChatModel 同一套 provider/策略/缓存机制，不另造轮子。</li>
 *   <li><b>配置来自 sys_config</b>：取 configType=llm、modelType=embedding 的默认配置，
 *       后台"配置管理"里可视化维护，代码里不写死模型名和密钥。</li>
 * </ul>
 *
 * <p>环境变量（启动脚本里设置）：
 * <pre>
 *   QDRANT_HOST        默认 192.168.10.132
 *   QDRANT_PORT        默认 6334（gRPC；6333 是 HTTP/控制台）
 *   QDRANT_COLLECTION  默认 xiaozhi_knowledge
 *   RAG_ENABLED        默认 true
 * </pre>
 */
@Slf4j
@Component
public class QdrantRagService {

    /** Qdrant 的 gRPC 口（6333 是 HTTP 与 Web 控制台，Spring AI 走 gRPC）。 */
    private static final String DEFAULT_HOST = "192.168.10.132";
    private static final int DEFAULT_PORT = 6334;
    private static final String DEFAULT_COLLECTION = "xiaozhi_knowledge";

    private final ChatModelFactory chatModelFactory;
    private final ConfigLookup configLookup;

    private final String host;
    private final int port;
    private final String collection;
    private final boolean enabled;

    private volatile QdrantClient client;
    private volatile EmbeddingModel embeddingModel;
    private volatile VectorStore vectorStore;

    public QdrantRagService(ChatModelFactory chatModelFactory, ConfigLookup configLookup, Environment env) {
        this.chatModelFactory = chatModelFactory;
        this.configLookup = configLookup;
        this.host = env.getProperty("QDRANT_HOST", DEFAULT_HOST);
        this.port = parseInt(env.getProperty("QDRANT_PORT", String.valueOf(DEFAULT_PORT)), DEFAULT_PORT);
        this.collection = env.getProperty("QDRANT_COLLECTION", DEFAULT_COLLECTION);
        this.enabled = Boolean.parseBoolean(env.getProperty("RAG_ENABLED", "true"));
        log.info("RAG(Qdrant) 初始化: host={}:{}, collection={}, enabled={}", host, port, collection, enabled);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getCollection() {
        return collection;
    }

    /** Qdrant 客户端（懒加载，失败会抛异常，由调用方转成可读提示）。 */
    public synchronized QdrantClient client() {
        if (client == null) {
            QdrantGrpcClient grpc = QdrantGrpcClient.newBuilder(host, port, false)
                    .withTimeout(Duration.ofSeconds(5))
                    .build();
            client = new QdrantClient(grpc);
            log.info("Qdrant 客户端已创建: {}:{} (gRPC)", host, port);
        }
        return client;
    }

    /** 取 embedding 的默认配置（configType=llm、modelType=embedding）。 */
    public ConfigBO embeddingConfig() {
        return configLookup.getDefaultConfig("llm", ConfigBO.ModelType.embedding.getValue());
    }

    /** EmbeddingModel（懒加载，复用项目工厂）。 */
    public synchronized EmbeddingModel embeddingModel() {
        if (embeddingModel == null) {
            ConfigBO config = embeddingConfig();
            if (config == null) {
                throw new IllegalStateException(
                        "缺少 embedding 配置：请到【配置管理】新增一条 configType=llm、modelType=embedding 的默认配置"
                                + "（provider=openai，模型名 text-embedding-v3，apiUrl 指向百炼兼容模式）");
            }
            embeddingModel = chatModelFactory.getEmbeddingModel(config);
            log.info("EmbeddingModel 已创建: provider={}, model={}", config.getProvider(), config.getConfigName());
        }
        return embeddingModel;
    }

    /**
     * 向量库（懒加载）。
     *
     * <p>initializeSchema=true：首次访问时若集合不存在则自动按 embedding 维度创建。
     */
    public synchronized VectorStore vectorStore() {
        if (vectorStore == null) {
            QdrantVectorStore store = QdrantVectorStore.builder(client(), embeddingModel())
                    .collectionName(collection)
                    .initializeSchema(true)
                    .build();
            // ★ 必须手动调一次 afterPropertiesSet()：
            // QdrantVectorStore 是 InitializingBean，建集合（initializeSchema）发生在该回调里；
            // 而这里是手动 new 的（不是 Spring bean），生命周期回调不会自动触发，
            // 不手动调就会一直报 NOT_FOUND: Collection `xxx` doesn't exist!
            try {
                store.afterPropertiesSet();
            } catch (Exception e) {
                throw new IllegalStateException("初始化 Qdrant 集合失败（collection=" + collection + "）：" + brief(e), e);
            }
            vectorStore = store;
            log.info("QdrantVectorStore 已创建并完成建表检查: collection={}", collection);
        }
        return vectorStore;
    }

    /**
     * 连通性自检（给 /api/knowledge/ping 用）。
     *
     * <p>只做只读检查，不写数据、不创建集合；每一步失败都转成字段返回，不抛异常。
     */
    public Map<String, Object> ping() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("qdrantAddress", host + ":" + port);
        result.put("collection", collection);

        // 1) Qdrant 是否可达 + 集合是否存在
        try {
            Collections.CollectionInfo info = client()
                    .getCollectionInfoAsync(collection, Duration.ofSeconds(5))
                    .get(5, TimeUnit.SECONDS);
            result.put("qdrantReachable", true);
            result.put("collectionExists", true);
            result.put("pointsCount", info.getPointsCount());
            try {
                result.put("vectorSize", readVectorSize(info));
            } catch (Exception ignored) {
                // protobuf 层级结构变化时不影响整体自检
            }
        } catch (Exception e) {
            // 连不上 / 集合不存在都会走到这里；区分一下更利于排查
            boolean reachable = false;
            try {
                // 用一个不存在的集合探一下：能拿到响应说明服务是通的
                client().getCollectionInfoAsync("__probe__", Duration.ofSeconds(3)).get(3, TimeUnit.SECONDS);
                reachable = true;
            } catch (Exception probeError) {
                reachable = probeError.getMessage() != null && !probeError.getMessage().contains("UNAVAILABLE");
            }
            result.put("qdrantReachable", reachable);
            result.put("collectionExists", false);
            result.put("note", "集合不存在（首次写入时自动创建）或查询失败：" + brief(e));
        }

        // 2) embedding 配置是否就绪
        ConfigBO config = embeddingConfig();
        if (config == null) {
            result.put("embeddingConfigured", false);
            result.put("hint", "缺少 embedding 配置：配置管理 → 新增 configType=llm / modelType=embedding（text-embedding-v3）");
        } else {
            result.put("embeddingConfigured", true);
            result.put("embeddingProvider", config.getProvider());
            result.put("embeddingModel", config.getConfigName());
            result.put("embeddingApiUrl", config.getApiUrl());
        }
        return result;
    }

    private static long readVectorSize(Collections.CollectionInfo info) {
        return info.getConfig().getParams().getVectorsConfig().getParams().getSize();
    }

    private static String brief(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) + "..." : message;
    }

    @PreDestroy
    public synchronized void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("关闭 Qdrant 客户端失败: {}", e.getMessage());
            } finally {
                client = null;
            }
        }
    }
}