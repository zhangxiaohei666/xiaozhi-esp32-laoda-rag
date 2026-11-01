package com.xiaozhi.knowledge.dal.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaozhi.knowledge.dal.mysql.dataobject.KnowledgeDocDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库文档表 Mapper。
 *
 * <p>列表查询按「非管理员只看自己的 + 所有 shared」过滤，直接在 Service 里用 LambdaQueryWrapper 拼，
 * 不需要自定义 XML —— 条件简单，留在代码里比放 XML 更好读。
 */
@Mapper
public interface KnowledgeDocMapper extends BaseMapper<KnowledgeDocDO> {
}