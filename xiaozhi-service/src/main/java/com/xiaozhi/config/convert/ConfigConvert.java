package com.xiaozhi.config.convert;

import com.xiaozhi.common.model.bo.ConfigBO;
import com.xiaozhi.common.model.req.ConfigCreateReq;
import com.xiaozhi.common.model.req.ConfigTestReq;
import com.xiaozhi.common.model.req.ConfigUpdateReq;
import com.xiaozhi.common.model.resp.ConfigResp;
import com.xiaozhi.config.dal.mysql.dataobject.ConfigDO;
import com.xiaozhi.config.domain.AiConfig;
import com.xiaozhi.common.util.EnvPlaceholder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.springframework.util.StringUtils;

@Mapper(componentModel = "spring")
public interface ConfigConvert {

    /**
     * 读取路径：把密钥字段里的环境变量占位符解析成真实值。
     *
     * <p>只在「库 → 内存」这一步解析，所以数据库里始终只存 {@code ${deepseek-api}} 这类占位符，
     * 真实密钥留在环境变量中；写入路径（见下面的 Create/Update 方法）不做解析，
     * 后台页面里填什么就存什么，不会把明文落库。
     */
    @Mapping(target = "apiKey", source = "apiKey", qualifiedByName = "resolveEnv")
    @Mapping(target = "apiSecret", source = "apiSecret", qualifiedByName = "resolveEnv")
    @Mapping(target = "ak", source = "ak", qualifiedByName = "resolveEnv")
    @Mapping(target = "sk", source = "sk", qualifiedByName = "resolveEnv")
    ConfigBO toBO(ConfigDO configDO);

    @Mapping(target = "configId", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "createTime", ignore = true)
    @Mapping(target = "updateTime", ignore = true)
    @Mapping(target = "apiKey", source = "apiKey", qualifiedByName = "blankToNull")
    @Mapping(target = "apiSecret", source = "apiSecret", qualifiedByName = "blankToNull")
    @Mapping(target = "ak", source = "ak", qualifiedByName = "blankToNull")
    @Mapping(target = "sk", source = "sk", qualifiedByName = "blankToNull")
    ConfigBO toBO(ConfigCreateReq req);

    @Mapping(target = "configId", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "createTime", ignore = true)
    @Mapping(target = "updateTime", ignore = true)
    @Mapping(target = "configType", ignore = true)
    @Mapping(target = "state", ignore = true)
    @Mapping(target = "apiKey", source = "apiKey", qualifiedByName = "blankToNull")
    @Mapping(target = "apiSecret", source = "apiSecret", qualifiedByName = "blankToNull")
    @Mapping(target = "ak", source = "ak", qualifiedByName = "blankToNull")
    @Mapping(target = "sk", source = "sk", qualifiedByName = "blankToNull")
    ConfigBO toBO(ConfigUpdateReq req);

    ConfigResp toResp(ConfigBO configBO);

    /**
     * 写路径出参：字段全部取自刚落库的聚合根，时间戳由 Repository 在写完后回填。
     * <p>密钥字段不在 ConfigResp 里，不会随出参外泄。
     */
    @Mapping(target = "isDefault", expression = "java(config.isDefault() ? \"1\" : \"0\")")
    ConfigResp toResp(AiConfig config);

    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "createTime", ignore = true)
    @Mapping(target = "updateTime", ignore = true)
    @Mapping(target = "contextLength", ignore = true)
    @Mapping(target = "apiKey", source = "apiKey", qualifiedByName = "resolveEnv")
    @Mapping(target = "apiSecret", source = "apiSecret", qualifiedByName = "resolveEnv")
    @Mapping(target = "ak", source = "ak", qualifiedByName = "resolveEnv")
    @Mapping(target = "sk", source = "sk", qualifiedByName = "resolveEnv")
    ConfigBO toBO(ConfigTestReq req);

    /** 密钥字段的空白串统一规范成 null，使「没填」在全仓只有 null 一种表示。 */
    @Named("blankToNull")
    static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    /**
     * 「先规范化空值，再解析环境变量占位符」——用于读取路径与配置测试路径。
     *
     * <p>例：库里存 {@code ${deepseek-api}} → 这里替换成环境变量 {@code deepseek-api} 的值；
     * 占位符存在但环境变量没配时返回 null（当作「没填」），绝不把 {@code ${...}} 字面量当密钥发出去。
     * 见 {@link EnvPlaceholder}。
     */
    @Named("resolveEnv")
    static String resolveEnv(String value) {
        return EnvPlaceholder.resolve(blankToNull(value));
    }
}
