package com.platform.conversation.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.conversation.entity.ChatCheckpoint;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ChatCheckpointMapper extends BaseMapper<ChatCheckpoint> {

    @InterceptorIgnore(tenantLine = "true")
    @Delete("""
            DELETE FROM chat_checkpoint
            WHERE updated_at < #{cutoff}
            LIMIT #{batch}
            """)
    int deleteOld(@Param("cutoff") java.time.LocalDateTime cutoff,
                  @Param("batch") int batch);
}
