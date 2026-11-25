package com.xiaozhi.common.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 普通用户维护自己的私人角色。
 * <p>
 * 刻意只有三个字段：模型、STT、VAD、音调语速等引擎参数一律由服务端从公共默认角色继承；
 * 头像与"我的默认"按 2026-11-26 的裁决不做。用户传得再多也不会被读。
 */
@Data
@Schema(description = "我的角色保存请求")
public class MyRoleReq {

    @Schema(description = "角色名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "角色名称不能为空")
    @Size(max = 50, message = "角色名称不能超过50个字符")
    private String roleName;

    @Schema(description = "角色提示词")
    @Size(max = 4000, message = "提示词不能超过4000个字符")
    private String roleDesc;

    @Schema(description = "音色值；不传表示沿用公共默认角色的音色")
    @Size(max = 120, message = "音色值不合法")
    private String voiceName;
}
