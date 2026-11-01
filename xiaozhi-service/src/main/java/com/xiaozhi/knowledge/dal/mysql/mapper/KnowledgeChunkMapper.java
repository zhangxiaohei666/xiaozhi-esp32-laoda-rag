package com.xiaozhi.knowledge.dal.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaozhi.knowledge.dal.mysql.dataobject.KnowledgeChunkDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库分块目录 Mapper。
 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkDO> {
}