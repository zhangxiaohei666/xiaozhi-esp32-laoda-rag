package com.xiaozhi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaozhi.common.model.bo.ConfigBO;
import com.xiaozhi.common.model.bo.RoleBO;
import com.xiaozhi.common.model.resp.VoiceOptionsResp;
import com.xiaozhi.config.service.ConfigService;
import com.xiaozhi.role.service.RoleService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 普通用户建角色时的音色白名单。
 * <p>
 * 白名单固定为 {@code voices/featured.json} 里的 10 个；是否可用再看 admin 有没有启用对应
 * provider 的 TTS 配置。Edge 不依赖配置，恒定可用。这样既能把复刻音色开放给用户，
 * 又不会因为开放注册把 admin 的云厂商额度无上限暴露出去。
 */
@Service
public class VoiceOptionAppService {

    /** 公共角色的所有者，与 RoleServiceImpl / UserAppService 同一口径 */
    private static final int ADMIN_USER_ID = 1;

    /** Edge 音色不由 sys_config 提供，沿用前端的 -1 约定 */
    public static final int EDGE_TTS_ID = -1;

    private static final Map<String, String> PROVIDER_LABELS = Map.of(
        "edge", "微软Edge",
        "aliyun", "阿里云"
    );

    @Resource
    private ConfigService configService;

    @Resource
    private RoleService roleService;

    @Resource
    private ObjectMapper objectMapper;

    private List<FeaturedVoice> featuredVoices = List.of();

    @PostConstruct
    void loadFeaturedVoices() {
        try (InputStream in = new ClassPathResource("voices/featured.json").getInputStream()) {
            FeaturedVoiceFile file = objectMapper.readValue(in, FeaturedVoiceFile.class);
            featuredVoices = file.getVoices() == null ? List.of() : List.copyOf(file.getVoices());
        } catch (Exception e) {
            throw new IllegalStateException("音色白名单 voices/featured.json 加载失败", e);
        }
    }

    /** 当前用户可见的音色；provider 没启用配置时该组整体消失 */
    public VoiceOptionsResp options() {
        List<ConfigBO> configs = configService.listBO(ADMIN_USER_ID, "tts", null, null, null, "1");
        Map<String, ConfigBO> providerConfig = pickProviderConfig(configs);

        Map<String, VoiceOptionsResp.VoiceGroup> groups = new LinkedHashMap<>();
        for (FeaturedVoice voice : featuredVoices) {
            Integer ttsId = resolveTtsId(voice.getProvider(), providerConfig);
            if (ttsId == null) {
                continue;
            }
            VoiceOptionsResp.VoiceGroup group = groups.computeIfAbsent(voice.getProvider(), provider -> {
                VoiceOptionsResp.VoiceGroup g = new VoiceOptionsResp.VoiceGroup();
                g.setProvider(provider);
                g.setProviderLabel(PROVIDER_LABELS.getOrDefault(provider, provider));
                g.setTtsId(ttsId);
                return g;
            });
            VoiceOptionsResp.VoiceItem item = new VoiceOptionsResp.VoiceItem();
            item.setValue(voice.getValue());
            item.setLabel(voice.getLabel());
            group.getVoices().add(item);
        }

        RoleBO defaultRole = roleService.getPublicDefaultBO();
        VoiceOptionsResp resp = new VoiceOptionsResp();
        resp.setGroups(new ArrayList<>(groups.values()));
        if (defaultRole != null) {
            resp.setDefaultTtsId(defaultRole.getTtsId());
            resp.setDefaultVoiceName(defaultRole.getVoiceName());
        }
        return resp;
    }

    /**
     * 校验并返回白名单条目；不在清单、或所属 provider 当前不可用都会拒绝。
     * 保存角色时只认这里的返回，不接受前端直接传 ttsId。
     */
    public FeaturedVoice requireAvailableVoice(String voiceName) {
        if (voiceName == null || voiceName.isBlank()) {
            throw new IllegalArgumentException("音色值不能为空");
        }
        FeaturedVoice match = featuredVoices.stream()
            .filter(voice -> voice.getValue().equals(voiceName.trim()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("该音色不在可选清单内"));

        List<ConfigBO> configs = configService.listBO(ADMIN_USER_ID, "tts", null, null, null, "1");
        if (resolveTtsId(match.getProvider(), pickProviderConfig(configs)) == null) {
            throw new IllegalArgumentException("该音色所属的语音服务当前未启用");
        }
        return match;
    }

    /** 白名单 provider 当前实际使用的 ttsId；Edge 为 -1，provider 不可用时 null */
    public Integer ttsIdOfProvider(String provider) {
        List<ConfigBO> configs = configService.listBO(ADMIN_USER_ID, "tts", null, null, null, "1");
        return resolveTtsId(provider, pickProviderConfig(configs));
    }

    private Integer resolveTtsId(String provider, Map<String, ConfigBO> providerConfig) {
        if ("edge".equals(provider)) {
            return EDGE_TTS_ID;
        }
        ConfigBO config = providerConfig.get(provider);
        return config == null ? null : config.getConfigId();
    }

    /** 同一 provider 多份配置：默认的优先，其次 configId 最小，保证结果稳定 */
    private Map<String, ConfigBO> pickProviderConfig(List<ConfigBO> configs) {
        Map<String, ConfigBO> picked = new LinkedHashMap<>();
        configs.stream()
            .filter(config -> config.getProvider() != null)
            .sorted(Comparator
                .comparing((ConfigBO config) -> !"1".equals(config.getIsDefault()))
                .thenComparing(ConfigBO::getConfigId, Comparator.nullsLast(Integer::compareTo)))
            .forEach(config -> picked.putIfAbsent(config.getProvider(), config));
        return picked;
    }

    @Data
    public static class FeaturedVoice {
        private String provider;
        private String value;
        private String label;
    }

    @Data
    public static class FeaturedVoiceFile {
        private List<FeaturedVoice> voices;
    }
}
