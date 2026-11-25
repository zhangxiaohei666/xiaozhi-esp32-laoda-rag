package com.xiaozhi.role;

import com.xiaozhi.common.exception.ResourceNotFoundException;
import com.xiaozhi.common.exception.UnauthorizedException;
import com.xiaozhi.common.model.bo.RoleBO;
import com.xiaozhi.common.model.req.MyRoleReq;
import com.xiaozhi.common.model.resp.RoleResp;
import com.xiaozhi.config.VoiceOptionAppService;
import com.xiaozhi.role.convert.RoleConvert;
import com.xiaozhi.role.domain.Role;
import com.xiaozhi.role.domain.repository.RoleRepository;
import com.xiaozhi.role.domain.vo.AudioConfig;
import com.xiaozhi.role.domain.vo.LlmConfig;
import com.xiaozhi.role.domain.vo.VoiceConfig;
import com.xiaozhi.role.service.RoleService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 普通用户维护自己的私人角色。
 * <p>
 * 与管理员使用的 {@link RoleAppService} 分开：管理端可以指定完整引擎参数、头像和默认标记，
 * 这里只允许名称/提示词/音色三个字段，其余一律从公共默认角色继承。管理员仍然可以走
 * {@code /api/role} 管理公共角色，但公共角色不经过本服务修改。
 */
@Service
public class MyRoleAppService {

    private static final int ADMIN_USER_ID = 1;
    private static final String OPEN = "1";

    @Resource
    private RoleService roleService;

    @Resource
    private RoleRepository roleRepository;

    @Resource
    private RoleConvert roleConvert;

    @Resource
    private VoiceOptionAppService voiceOptionAppService;

    @Resource
    private RoleAppService roleAppService;

    public List<RoleResp> list(Integer userId) {
        return roleService.listPrivateBO(userId).stream()
            .map(roleConvert::toResp)
            .toList();
    }

    @Transactional
    public RoleResp create(MyRoleReq req, Integer userId) {
        RoleBO base = roleService.getPublicDefaultBO();
        if (base == null) {
            throw new IllegalStateException("系统尚未配置可用的公共角色，请联系管理员");
        }
        String roleName = normalizeName(req.getRoleName());
        requireNameAvailable(userId, roleName, null);

        VoiceSelection voice = resolveVoice(req.getVoiceName(), base);
        Role role = Role.newRole(userId, roleName, req.getRoleDesc(), null,
            new LlmConfig(base.getModelId(), base.getTemperature(), base.getTopP()),
            new VoiceConfig(voice.ttsId(), base.getSttId(), voice.voiceName(),
                base.getTtsPitch(), base.getTtsSpeed(), base.getSttHotwords()),
            new AudioConfig(base.getVadEnergyTh(), base.getVadSpeechTh(),
                base.getVadSilenceTh(), base.getVadSilenceMs()),
            false,
            base.getInactiveTimeoutSeconds());
        roleRepository.save(role);
        return roleConvert.toResp(role);
    }

    @Transactional
    public RoleResp update(Integer roleId, MyRoleReq req, Integer userId) {
        Role role = loadOwnedPrivateRole(roleId, userId);
        String roleName = normalizeName(req.getRoleName());
        requireNameAvailable(userId, roleName, roleId);

        VoiceConfig voicePatch = null;
        if (req.getVoiceName() != null) {
            RoleBO base = roleService.getPublicDefaultBO();
            if (base == null) {
                throw new IllegalStateException("系统尚未配置可用的公共角色，请联系管理员");
            }
            VoiceSelection voice = resolveVoice(req.getVoiceName(), base);
            voicePatch = new VoiceConfig(voice.ttsId(), null, voice.voiceName(), null, null, null);
        }
        role.update(roleName, req.getRoleDesc(), null, null, voicePatch, null, null, null);
        roleRepository.save(role);
        return roleConvert.toResp(role);
    }

    @Transactional
    public void delete(Integer roleId, Integer userId) {
        loadOwnedPrivateRole(roleId, userId);
        // 设备绑定、会话/摘要清理和公共删除守卫统一走管理端同一套逻辑
        roleAppService.delete(roleId);
    }

    private Role loadOwnedPrivateRole(Integer roleId, Integer userId) {
        Role role = roleRepository.findById(roleId)
            .orElseThrow(() -> new ResourceNotFoundException("角色不存在或无权访问"));
        // 公共角色是管理员的角色，但对普通用户来说它是"不可编辑的公共资源"，
        // 先给这个明确提示，避免把"不是你的"和"这是公共角色"混成一句
        if (role.getUserId() != null && role.getUserId() == ADMIN_USER_ID && role.isOpen()) {
            throw new UnauthorizedException("公共角色请由管理员在角色管理中维护");
        }
        if (!userId.equals(role.getUserId())) {
            throw new UnauthorizedException("角色不归属当前用户");
        }
        return role;
    }

    private String normalizeName(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            throw new IllegalArgumentException("角色名称不能为空");
        }
        return roleName.trim();
    }

    private void requireNameAvailable(Integer userId, String roleName, Integer excludeRoleId) {
        if (roleService.existsPublicRoleName(roleName, excludeRoleId)) {
            throw new IllegalArgumentException("该名称与公共角色重复，请换一个名字");
        }
        if (roleService.existsRoleName(userId, roleName, excludeRoleId)) {
            throw new IllegalArgumentException("你已经有同名角色了");
        }
    }

    /** 音色为空表示跟随公共默认；非空必须在白名单且 provider 当前可用 */
    private VoiceSelection resolveVoice(String voiceName, RoleBO base) {
        if (voiceName == null || voiceName.isBlank()) {
            return new VoiceSelection(base.getTtsId(), base.getVoiceName());
        }
        VoiceOptionAppService.FeaturedVoice voice = voiceOptionAppService.requireAvailableVoice(voiceName);
        Integer ttsId = voiceOptionAppService.ttsIdOfProvider(voice.getProvider());
        if (ttsId == null) {
            throw new IllegalStateException("该音色所属的语音服务当前未启用");
        }
        return new VoiceSelection(ttsId, voice.getValue());
    }

    private record VoiceSelection(Integer ttsId, String voiceName) {
    }
}
