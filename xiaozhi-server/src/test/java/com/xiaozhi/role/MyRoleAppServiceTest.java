package com.xiaozhi.role;

import com.xiaozhi.common.exception.UnauthorizedException;
import com.xiaozhi.common.model.bo.RoleBO;
import com.xiaozhi.common.model.req.MyRoleReq;
import com.xiaozhi.common.model.resp.RoleResp;
import com.xiaozhi.config.VoiceOptionAppService;
import com.xiaozhi.role.convert.RoleConvert;
import com.xiaozhi.role.domain.Role;
import com.xiaozhi.role.domain.repository.RoleRepository;
import com.xiaozhi.role.service.RoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyRoleAppServiceTest {

    private static final int USER_ID = 7;
    private static final int ROLE_ID = 88;

    @Mock
    private RoleService roleService;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private VoiceOptionAppService voiceOptionAppService;

    @Mock
    private RoleAppService roleAppService;

    private final RoleConvert roleConvert = Mappers.getMapper(RoleConvert.class);

    private MyRoleAppService myRoleAppService;

    @BeforeEach
    void setUp() {
        myRoleAppService = new MyRoleAppService();
        ReflectionTestUtils.setField(myRoleAppService, "roleService", roleService);
        ReflectionTestUtils.setField(myRoleAppService, "roleRepository", roleRepository);
        ReflectionTestUtils.setField(myRoleAppService, "roleConvert", roleConvert);
        ReflectionTestUtils.setField(myRoleAppService, "voiceOptionAppService", voiceOptionAppService);
        ReflectionTestUtils.setField(myRoleAppService, "roleAppService", roleAppService);
    }

    @Test
    void createInheritsEngineSettingsFromPublicDefaultRole() {
        when(roleService.getPublicDefaultBO()).thenReturn(publicDefaultRole());
        assignIdOnSave();

        MyRoleReq req = new MyRoleReq();
        req.setRoleName("学习助手");
        req.setRoleDesc("只讲重点");
        req.setVoiceName("");

        RoleResp resp = myRoleAppService.create(req, USER_ID);

        assertThat(resp.getRoleId()).isEqualTo(ROLE_ID);
        assertThat(resp.getModelId()).isEqualTo(8);
        assertThat(resp.getTemperature()).isEqualTo(0.3);
        assertThat(resp.getTopP()).isEqualTo(0.8);
        assertThat(resp.getTtsId()).isEqualTo(4);
        assertThat(resp.getVoiceName()).isEqualTo("cosyvoice-v2:laoda");
        assertThat(resp.getSttId()).isEqualTo(6);
        assertThat(resp.getVadSilenceMs()).isEqualTo(700);
        assertThat(resp.getIsOpen()).isEqualTo("0");
        assertThat(resp.getIsDefault()).isEqualTo("0");
        assertThat(resp.getState()).isEqualTo(Role.STATE_ENABLED);
        verify(roleRepository).save(any(Role.class));
    }

    @Test
    void createUsesWhitelistedVoiceAndServerResolvedTtsId() {
        when(roleService.getPublicDefaultBO()).thenReturn(publicDefaultRole());
        VoiceOptionAppService.FeaturedVoice voice = featured("aliyun", "longxiaochun_v2");
        when(voiceOptionAppService.requireAvailableVoice("longxiaochun_v2")).thenReturn(voice);
        when(voiceOptionAppService.ttsIdOfProvider("aliyun")).thenReturn(4);
        assignIdOnSave();

        MyRoleReq req = new MyRoleReq();
        req.setRoleName("小助手");
        req.setVoiceName("longxiaochun_v2");

        RoleResp resp = myRoleAppService.create(req, USER_ID);

        assertThat(resp.getVoiceName()).isEqualTo("longxiaochun_v2");
        assertThat(resp.getTtsId()).isEqualTo(4);
        // 前端只传音色值，configId 必须由服务端白名单解析
        verify(voiceOptionAppService).ttsIdOfProvider("aliyun");
    }

    @Test
    void createRejectsNameAlreadyUsedByPublicRole() {
        when(roleService.getPublicDefaultBO()).thenReturn(publicDefaultRole());
        when(roleService.existsPublicRoleName("牢大", null)).thenReturn(true);

        MyRoleReq req = new MyRoleReq();
        req.setRoleName("牢大");

        assertThatThrownBy(() -> myRoleAppService.create(req, USER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("公共角色");
        verify(roleRepository, never()).save(any());
    }

    @Test
    void createRejectsDuplicatePrivateName() {
        when(roleService.getPublicDefaultBO()).thenReturn(publicDefaultRole());
        when(roleService.existsRoleName(USER_ID, "学习助手", null)).thenReturn(true);

        MyRoleReq req = new MyRoleReq();
        req.setRoleName("学习助手");

        assertThatThrownBy(() -> myRoleAppService.create(req, USER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("同名");
    }

    @Test
    void updateRejectsAnotherUsersRole() {
        when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(ownedRole(USER_ID + 1, false)));

        MyRoleReq req = new MyRoleReq();
        req.setRoleName("改名");

        assertThatThrownBy(() -> myRoleAppService.update(ROLE_ID, req, USER_ID))
            .isInstanceOf(UnauthorizedException.class)
            .hasMessage("角色不归属当前用户");
        verify(roleRepository, never()).save(any());
    }

    @Test
    void updateRejectsPublicRole() {
        when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(ownedRole(1, true)));

        MyRoleReq req = new MyRoleReq();
        req.setRoleName("改名");

        assertThatThrownBy(() -> myRoleAppService.update(ROLE_ID, req, USER_ID))
            .isInstanceOf(UnauthorizedException.class)
            .hasMessage("公共角色请由管理员在角色管理中维护");
    }

    @Test
    void updateOnlyTouchesNamePromptAndVoice() {
        Role role = ownedRole(USER_ID, false);
        when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));
        when(roleService.getPublicDefaultBO()).thenReturn(publicDefaultRole());
        when(voiceOptionAppService.requireAvailableVoice("zh-CN-YunxiNeural"))
            .thenReturn(featured("edge", "zh-CN-YunxiNeural"));
        when(voiceOptionAppService.ttsIdOfProvider("edge")).thenReturn(-1);

        MyRoleReq req = new MyRoleReq();
        req.setRoleName("新名字");
        req.setRoleDesc("新提示词");
        req.setVoiceName("zh-CN-YunxiNeural");

        RoleResp resp = myRoleAppService.update(ROLE_ID, req, USER_ID);

        assertThat(resp.getRoleName()).isEqualTo("新名字");
        assertThat(resp.getRoleDesc()).isEqualTo("新提示词");
        assertThat(resp.getVoiceName()).isEqualTo("zh-CN-YunxiNeural");
        // 模型、STT、VAD 都不在私人角色的可编辑范围
        assertThat(resp.getModelId()).isEqualTo(8);
        assertThat(resp.getSttId()).isEqualTo(6);
        assertThat(resp.getIsOpen()).isEqualTo("0");
    }

    @Test
    void listReturnsOnlyOwnPrivateRoles() {
        RoleBO own = new RoleBO();
        own.setRoleId(ROLE_ID);
        own.setUserId(USER_ID);
        own.setRoleName("学习助手");
        own.setState("1");
        own.setIsOpen("0");
        own.setIsDefault("0");
        when(roleService.listPrivateBO(USER_ID)).thenReturn(List.of(own));

        List<RoleResp> result = myRoleAppService.list(USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRoleName()).isEqualTo("学习助手");
    }

    @Test
    void deleteChecksOwnershipBeforeDelegating() {
        when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(ownedRole(USER_ID, false)));

        myRoleAppService.delete(ROLE_ID, USER_ID);

        verify(roleAppService).delete(ROLE_ID);
    }

    private void assignIdOnSave() {
        doAnswer(invocation -> {
            invocation.getArgument(0, Role.class).assignId(ROLE_ID);
            return null;
        }).when(roleRepository).save(any(Role.class));
    }

    private static VoiceOptionAppService.FeaturedVoice featured(String provider, String value) {
        VoiceOptionAppService.FeaturedVoice voice = new VoiceOptionAppService.FeaturedVoice();
        voice.setProvider(provider);
        voice.setValue(value);
        voice.setLabel(value);
        return voice;
    }

    private static RoleBO publicDefaultRole() {
        RoleBO bo = new RoleBO();
        bo.setRoleId(1);
        bo.setUserId(1);
        bo.setRoleName("牢大");
        bo.setState("1");
        bo.setIsDefault("1");
        bo.setIsOpen("1");
        bo.setModelId(8);
        bo.setTemperature(0.3);
        bo.setTopP(0.8);
        bo.setTtsId(4);
        bo.setVoiceName("cosyvoice-v2:laoda");
        bo.setTtsPitch(1.0);
        bo.setTtsSpeed(1.0);
        bo.setSttId(6);
        bo.setVadEnergyTh(0.1f);
        bo.setVadSpeechTh(0.2f);
        bo.setVadSilenceTh(0.3f);
        bo.setVadSilenceMs(700);
        bo.setInactiveTimeoutSeconds(60);
        return bo;
    }

    private static Role ownedRole(int userId, boolean open) {
        return new Role(ROLE_ID, userId, null, "学习助手", "旧提示词", Role.STATE_ENABLED, open, false, 60,
            new com.xiaozhi.role.domain.vo.LlmConfig(8, 0.3, 0.8),
            new com.xiaozhi.role.domain.vo.VoiceConfig(4, 6, "cosyvoice-v2:laoda", 1.0, 1.0, null),
            new com.xiaozhi.role.domain.vo.AudioConfig(0.1f, 0.2f, 0.3f, 700),
            null, null);
    }
}
