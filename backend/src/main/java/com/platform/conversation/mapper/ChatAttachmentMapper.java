package com.platform.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.conversation.entity.ChatAttachment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChatAttachmentMapper extends BaseMapper<ChatAttachment> {

    /** 租户附件存储占用（配额校验用）。 */
    @Select("SELECT COALESCE(SUM(size_bytes), 0) FROM chat_attachment WHERE tenant_id = #{tenantId}")
    long sumSizeByTenant(@Param("tenantId") Long tenantId);
}
