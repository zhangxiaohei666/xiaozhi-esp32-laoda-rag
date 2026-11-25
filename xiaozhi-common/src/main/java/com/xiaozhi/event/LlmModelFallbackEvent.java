package com.xiaozhi.event;

/**
 * LLM 模型降级事件：角色这一轮实际使用的模型不是角色绑定的那个，而是回退到了默认模型。
 * <p>
 * 为什么要有事件：降级会让质量、成本、延迟特性同时变化，管理员必须能在监控/后台看到"谁降级了、
 * 从哪个配置降到哪个配置、为什么"，而不是只能翻日志。监听方可以是告警、指标、后台提示，互不影响。
 * <p>
 * 发布方：ChatModelFactory#resolveConfig。发布失败不会影响本轮对话（异常被吞并记 WARN）。
 *
 * @param roleName     角色名（日志与界面展示用）
 * @param roleId       角色 ID，可能为空（没有角色上下文的调用）
 * @param fromConfigId 角色原本绑定的模型配置 ID；角色未绑定模型时为空
 * @param toConfigId   实际使用的默认模型配置 ID
 * @param toProvider   实际使用配置的 provider（便于判断成本等级是否变化）
 * @param reason       降级原因，取值见 ChatModelFactory 里的三种：角色未绑定模型 / 配置不存在 / 配置已停用
 */
public record LlmModelFallbackEvent(String roleName,
                                    Integer roleId,
                                    Integer fromConfigId,
                                    Integer toConfigId,
                                    String toProvider,
                                    String reason) {
}