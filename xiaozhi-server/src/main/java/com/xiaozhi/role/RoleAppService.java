package com.xiaozhi.role;

import com.xiaozhi.common.model.req.RoleCreateReq;
import com.xiaozhi.common.model.req.RolePageReq;
import com.xiaozhi.common.model.req.RoleUpdateReq;
import com.xiaozhi.common.model.PageResult;
import com.xiaozhi.common.model.resp.RoleResp;
import com.xiaozhi.common.exception.ResourceNotFoundException;
import com.xiaozhi.device.service.DeviceService;
import com.xiaozhi.message.service.ConversationService;
import com.xiaozhi.role.convert.RoleConvert;
import com.xiaozhi.role.domain.Role;
import com.xiaozhi.role.domain.repository.RoleRepository;
import com.xiaozhi.role.domain.vo.AudioConfig;
import com.xiaozhi.role.domain.vo.LlmConfig;
import com.xiaozhi.role.domain.vo.VoiceConfig;
import com.xiaozhi.role.service.RoleService;
import com.xiaozhi.summary.service.SummaryService;
import com.xiaozhi.user.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色领域应用服务。
 * <p>
 * 职责：编排 Controller → Domain Service 之间的流程，包括：
 * <ul>
 *   <li>Req/Resp ↔ BO 转换</li>
 *   <li>Req/Resp 转换与业务编排</li>
 * </ul>
 */
@Service
public class RoleAppService {

    @Resource
    private RoleService roleService;

    @Resource
    private RoleRepository roleRepository;

    @Resource
    private RoleConvert roleConvert;

    @Resource
    private DeviceService deviceService;

    @Resource
    private SummaryService summaryService;

    @Resource
    private ConversationService conversationService;

    @Resource
    private UserService userService;

    /** 公共角色的所有者：V1 初始化的那个管理员；公共行只能关闭，不能删除 */
    private static final int ADMIN_USER_ID = 1;

    public PageResult<RoleResp> page(RolePageReq req, Integer userId) {
        RolePageReq r = req == null ? new RolePageReq() : req;
        // RolePageReq.state 没有默认值，不传就不过滤停用角色；非管理员只应看到启用角色（公共行也按此收窄）
        if (!userService.isAdmin(userId)) {
            r.setState(Role.STATE_ENABLED);
        }
        return roleService.page(r.getPageNo(), r.getPageSize(),
                r.getRoleId(), r.getRoleName(), r.getIsDefault(), r.getState(), userId)
            .map(roleConvert::toResp);
    }

    @Transactional
    public RoleResp create(RoleCreateReq req, Integer userId) {
        Role role = Role.newRole(userId, req.getRoleName(), req.getRoleDesc(), req.getAvatar(),
                new LlmConfig(req.getModelId(), req.getTemperature(), req.getTopP()),
                new VoiceConfig(req.getTtsId(), req.getSttId(), req.getVoiceName(), req.getTtsPitch(), req.getTtsSpeed(), req.getSttHotwords()),
                new AudioConfig(req.getVadEnergyTh(), req.getVadSpeechTh(), req.getVadSilenceTh(), req.getVadSilenceMs()),
                "1".equals(req.getIsDefault()),
                req.getInactiveTimeoutSeconds());
        roleRepository.save(role);

        return roleConvert.toResp(role);
    }

    @Transactional
    public RoleResp update(Integer roleId, RoleUpdateReq req) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("角色不存在或无权访问"));

        validateOpenChange(role, req);

        role.update(req.getRoleName(), req.getRoleDesc(), req.getAvatar(),
                new LlmConfig(req.getModelId(), req.getTemperature(), req.getTopP()),
                new VoiceConfig(req.getTtsId(), req.getSttId(), req.getVoiceName(), req.getTtsPitch(), req.getTtsSpeed(), req.getSttHotwords()),
                new AudioConfig(req.getVadEnergyTh(), req.getVadSpeechTh(), req.getVadSilenceTh(), req.getVadSilenceMs()),
                req.getIsDefault() == null ? null : "1".equals(req.getIsDefault()),
                req.getInactiveTimeoutSeconds());

        if (req.getIsOpen() != null) {
            role.changeOpen(req.getIsOpen());
        }
        
        if (Role.STATE_ENABLED.equals(req.getState())) {
            role.enable();
        } else if (Role.STATE_DISABLED.equals(req.getState())) {
            role.disable();
        }
        roleRepository.save(role);

        return roleConvert.toResp(role);
    }

    /**
     * 公共角色开关的三条硬约束：
     * <ol>
     *   <li>只有 admin 名下的角色能被标记为公共；</li>
     *   <li>公共默认角色必须保持开放（设备/注册兜底都指向它）；</li>
     *   <li>关闭后至少还要剩一个"开放且启用"的公共角色，否则普通用户无角色可用。</li>
     * </ol>
     * 校验都放在落库前；抛异常即事务回滚，不会出现"开关只改了一半"。
     */
    private void validateOpenChange(Role role, RoleUpdateReq req) {
        if (req.getIsOpen() == null) {
            // 没改开关，但可能通过 isDefault/state 把状态改成了非法组合
            boolean defaultAfter = req.getIsDefault() == null ? role.isDefault() : "1".equals(req.getIsDefault());
            boolean openAfter = role.isOpen();
            if (Integer.valueOf(ADMIN_USER_ID).equals(role.getUserId()) && defaultAfter && !openAfter) {
                throw new IllegalStateException("公共默认角色必须处于开放状态");
            }
            return;
        }

        if (!Integer.valueOf(ADMIN_USER_ID).equals(role.getUserId())) {
            throw new IllegalStateException("只有管理员名下的角色可以设置开放");
        }

        boolean openAfter = req.getIsOpen();
        boolean defaultAfter = req.getIsDefault() == null ? role.isDefault() : "1".equals(req.getIsDefault());
        String stateAfter = req.getState() == null ? role.getState() : req.getState();

        if (openAfter && !Role.STATE_ENABLED.equals(stateAfter)) {
            throw new IllegalStateException("停用的角色不能开放给全体用户");
        }
        if (!openAfter && role.isOpen() && role.isDefault()) {
            throw new IllegalStateException("公共默认角色必须保持开放，请先将其他公共角色设为默认");
        }
        if (defaultAfter && !openAfter) {
            throw new IllegalStateException("公共默认角色必须处于开放状态");
        }
        if (!openAfter && role.isOpen()) {
            // 停用的角色本来就不在"可用公共角色"里，关它不会减少供给，不该被最后一条守卫拦住
            if (Role.STATE_ENABLED.equals(role.getState()) && roleService.countOpenPublic() <= 1) {
                throw new IllegalStateException("至少保留一个开放且启用的公共角色");
            }
        }
    }


    /**
     * 删除角色。sys_device.roleId 上没有外键，硬删会留下悬空引用，绑定该角色的设备之后每次接入都会失败，
     * 因此仍有设备绑定时拒绝删除。
     * <p>
     * 改绑到别的角色的设备在这个角色下还留着对话摘要，换回来时接着用；角色删了就再也换不回来，一并删掉。
     * 网页聊天会话没有角色就续不上，连同会话消息一并删；设备消息是设备的历史，保留。
     */
    @Transactional
    public void delete(Integer roleId) {
        // 公共角色删一行等于毁全员：允许关闭开放 / 停用，但不允许删除
        roleRepository.findById(roleId).ifPresent(role -> {
            if (Integer.valueOf(ADMIN_USER_ID).equals(role.getUserId()) && role.isOpen()) {
                throw new IllegalStateException("公共角色不可删除，请先关闭开放开关");
            }
        });
        long boundDevices = countBoundDevices(roleId);
        if (boundDevices > 0) {
            throw new IllegalStateException("该角色仍绑定 " + boundDevices + " 台设备，请先将设备改绑到其他角色");
        }
        roleRepository.delete(roleId);
        summaryService.deleteByRoleId(roleId);
        conversationService.deleteByRole(roleId);
    }

    private long countBoundDevices(Integer roleId) {
        Long total = deviceService.page(1, 1, null, null, null, null, roleId, null).getTotal();
        return total == null ? 0 : total;
    }
}
