package com.xiaozhi.role.infrastructure.convert;

import com.xiaozhi.common.model.bo.RoleBO;
import com.xiaozhi.role.dal.mysql.dataobject.RoleDO;
import com.xiaozhi.role.domain.Role;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoleConverterTest {

    private final RoleConverter converter = new RoleConverter();

    @Test
    void preservesInactiveTimeoutAcrossDomainMappings() {
        RoleDO dataObject = roleDataObject(120);

        Role role = converter.toDomain(dataObject);

        assertThat(role.getInactiveTimeoutSeconds()).isEqualTo(120);
        assertThat(converter.toDataObject(role).getInactiveTimeoutSeconds()).isEqualTo(120);
        assertThat(converter.toBO(role).getInactiveTimeoutSeconds()).isEqualTo(120);
    }

    @Test
    void defaultsInactiveTimeoutForLegacyRoleData() {
        Role role = converter.toDomain(roleDataObject(null));

        assertThat(role.getInactiveTimeoutSeconds()).isEqualTo(RoleBO.DEFAULT_INACTIVE_TIMEOUT_SECONDS);
    }

    /** isOpen 在 DO/BO 是 "1"/"0"、在聚合根是 boolean，三个方向都要对上，少一环判定就恒 false */
    @Test
    void carriesIsOpenAcrossDomainMappings() {
        RoleDO dataObject = roleDataObject(120);
        dataObject.setIsOpen("1");

        Role role = converter.toDomain(dataObject);

        assertThat(role.isOpen()).isTrue();
        assertThat(converter.toDataObject(role).getIsOpen()).isEqualTo("1");
        assertThat(converter.toBO(role).getIsOpen()).isEqualTo("1");
    }

    @Test
    void closedRoleMapsToZeroOnWritePath() {
        RoleDO dataObject = roleDataObject(120);
        dataObject.setIsOpen("0");

        Role role = converter.toDomain(dataObject);

        assertThat(role.isOpen()).isFalse();
        assertThat(converter.toDataObject(role).getIsOpen()).isEqualTo("0");
        assertThat(converter.toBO(role).getIsOpen()).isEqualTo("0");
    }

    private RoleDO roleDataObject(Integer inactiveTimeoutSeconds) {
        RoleDO dataObject = new RoleDO();
        dataObject.setRoleId(1);
        dataObject.setUserId(2);
        dataObject.setRoleName("test-role");
        dataObject.setState("1");
        dataObject.setIsDefault("0");
        dataObject.setInactiveTimeoutSeconds(inactiveTimeoutSeconds);
        return dataObject;
    }
}
