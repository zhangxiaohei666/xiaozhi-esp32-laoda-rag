package com.xiaozhi.common.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 普通用户建角色时可选的音色清单。
 * <p>
 * 只暴露白名单里的 10 个音色和所属 ttsId，不下发 apiKey、configId 之外的凭据字段；
 * 用户传别的 voiceName 会被后端拒绝，不能靠改前端绕过额度限制。
 */
@Data
@Schema(description = "我的角色可选音色")
public class VoiceOptionsResp {

    @Schema(description = "公共默认角色当前使用的 ttsId；null 表示本地/Edge")
    private Integer defaultTtsId;

    @Schema(description = "公共默认角色当前使用的音色值")
    private String defaultVoiceName;

    @Schema(description = "按提供方分组的音色")
    private List<VoiceGroup> groups = new ArrayList<>();

    @Data
    @Schema(description = "同一 TTS 配置下的一组音色")
    public static class VoiceGroup {

        @Schema(description = "TTS 配置 ID；-1 表示 Edge 内置")
        private Integer ttsId;

        @Schema(description = "提供方标识")
        private String provider;

        @Schema(description = "提供方显示名")
        private String providerLabel;

        @Schema(description = "音色列表")
        private List<VoiceItem> voices = new ArrayList<>();
    }

    @Data
    @Schema(description = "单个音色")
    public static class VoiceItem {

        @Schema(description = "音色值，保存角色时原样回传")
        private String value;

        @Schema(description = "下拉显示名")
        private String label;
    }
}
