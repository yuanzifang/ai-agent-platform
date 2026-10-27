package com.platform.knowledge.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.knowledge.entity.KnowledgeOcrCache;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface KnowledgeOcrCacheMapper extends BaseMapper<KnowledgeOcrCache> {
    @InterceptorIgnore(tenantLine = "true")
    @Delete("DELETE FROM knowledge_ocr_cache WHERE last_used_at < #{cutoff} LIMIT #{limit}")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
