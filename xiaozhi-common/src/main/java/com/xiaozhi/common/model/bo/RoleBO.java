package com.xiaozhi.common.model.bo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
// 缓存里的历史 JSON 可能带新版本没有的字段（例如上一版把 isPublicRole() 序列化成了 publicRole），
// 忽略未知字段才能让升级后第一次读旧缓存不炸
@JsonIgnoreProperties(ignoreUnknown = true)
public class RoleBO {

    /*
     * 角色参数的默认值只在这里定义：service 的值对象补默认、读路径补 NULL 列、对话侧兜底、
     * 前端表单展示（经 /api/role/defaults）都引用它们，库表列默认与它们一致
     */
    public static final double DEFAULT_TEMPERATURE = 0.7d;
    public static final double DEFAULT_TOP_P = 0.9d;
    public static final double DEFAULT_TTS_PITCH = 1.0d;
    public static final double DEFAULT_TTS_SPEED = 1.0d;
    public static final float DEFAULT_VAD_SPEECH_TH = 0.5f;
    public static final float DEFAULT_VAD_SILENCE_TH = 0.3f;
    public static final float DEFAULT_VAD_ENERGY_TH = 0.01f;
    public static final int DEFAULT_VAD_SILENCE_MS = 800;
    public static final int DEFAULT_INACTIVE_TIMEOUT_SECONDS = 60;

    private Integer roleId;
    private Integer userId;
    private String avatar;
    private String roleName;
    private String roleDesc;
    private String voiceName;
    private Double ttsPitch = DEFAULT_TTS_PITCH;
    private Double ttsSpeed = DEFAULT_TTS_SPEED;
    private String state;
    private Integer ttsId;
    private Integer modelId;
    private Integer sttId;
    private String sttHotwords;
    private Double temperature = DEFAULT_TEMPERATURE;
    private Double topP = DEFAULT_TOP_P;
    private Float vadEnergyTh;
    private Float vadSpeechTh;
    private Float vadSilenceTh;
    private Integer vadSilenceMs;
    private Integer inactiveTimeoutSeconds = DEFAULT_INACTIVE_TIMEOUT_SECONDS;
    private String isDefault;
    /**
     * 公共角色开放标记："1" = admin 发布、全员可读可绑；"0" = 仅本人。
     * 接口层 {@code rolePublic} 检查器与 Service 层 {@code requireSameOwner} 都读它，且只有启用的才算公共。
     */
    private String isOpen;

    /** 公共角色判定：开放 + 启用，两个条件都占才算（停用的公共角色对新绑定关闭，已绑设备继续用） */
    @JsonIgnore // 计算属性，不是字段；不加会被当成 publicRole 写进 Redis JSON，反序列化时又没有对应 setter
    public boolean isPublicRole() {
        return "1".equals(isOpen) && "1".equals(state);
    }
}
