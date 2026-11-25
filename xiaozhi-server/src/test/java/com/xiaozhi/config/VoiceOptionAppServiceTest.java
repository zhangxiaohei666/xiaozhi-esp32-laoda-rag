package com.xiaozhi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaozhi.common.model.bo.ConfigBO;
import com.xiaozhi.common.model.bo.RoleBO;
import com.xiaozhi.common.model.resp.VoiceOptionsResp;
import com.xiaozhi.config.service.ConfigService;
import com.xiaozhi.role.service.RoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceOptionAppServiceTest {

    @Mock
    private ConfigService configService;

    @Mock
    private RoleService roleService;

    private VoiceOptionAppService service;

    @BeforeEach
    void setUp() {
        service = new VoiceOptionAppService();
        ReflectionTestUtils.setField(service, "configService", configService);
        ReflectionTestUtils.setField(service, "roleService", roleService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        service.loadFeaturedVoices();
    }

    @Test
    void returnsTenFeaturedVoicesWhenBothProvidersAreEnabled() {
        when(configService.listBO(1, "tts", null, null, null, "1"))
            .thenReturn(List.of(ttsConfig(4, "aliyun")));
        when(roleService.getPublicDefaultBO()).thenReturn(publicDefaultRole());

        VoiceOptionsResp resp = service.options();

        assertThat(resp.getGroups()).hasSize(2);
        assertThat(resp.getGroups().stream().flatMap(group -> group.getVoices().stream()).count())
            .isEqualTo(10);
        assertThat(resp.getGroups()).anySatisfy(group -> {
            if ("edge".equals(group.getProvider())) {
                assertThat(group.getTtsId()).isEqualTo(-1);
                assertThat(group.getVoices()).hasSize(3);
            }
        });
        assertThat(resp.getDefaultTtsId()).isEqualTo(4);
        assertThat(resp.getDefaultVoiceName()).isEqualTo("cosyvoice-v2:laoda");
    }

    @Test
    void hidesProviderVoicesWhenNoEnabledTtsConfigExists() {
        when(configService.listBO(1, "tts", null, null, null, "1")).thenReturn(List.of());
        when(roleService.getPublicDefaultBO()).thenReturn(null);

        VoiceOptionsResp resp = service.options();

        assertThat(resp.getGroups()).hasSize(1);
        assertThat(resp.getGroups().get(0).getProvider()).isEqualTo("edge");
        assertThat(resp.getGroups().get(0).getVoices()).hasSize(3);
    }

    @Test
    void rejectsVoiceOutsideTheFeaturedList() {
        assertThatThrownBy(() -> service.requireAvailableVoice("not-a-voice"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不在可选清单");
    }

    @Test
    void rejectsAliyunVoiceWhenProviderHasNoEnabledConfig() {
        when(configService.listBO(eq(1), eq("tts"), any(), any(), any(), eq("1")))
            .thenReturn(List.of());

        assertThatThrownBy(() -> service.requireAvailableVoice("longxiaochun_v2"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("未启用");
    }

    private static ConfigBO ttsConfig(int configId, String provider) {
        ConfigBO config = new ConfigBO();
        config.setConfigId(configId);
        config.setProvider(provider);
        config.setIsDefault("0");
        config.setState("1");
        return config;
    }

    private static RoleBO publicDefaultRole() {
        RoleBO bo = new RoleBO();
        bo.setRoleId(1);
        bo.setUserId(1);
        bo.setTtsId(4);
        bo.setVoiceName("cosyvoice-v2:laoda");
        return bo;
    }
}
