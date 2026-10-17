package com.platform.conversation.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.conversation.entity.ConversationShare;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ConversationShareMapper extends BaseMapper<ConversationShare> {

    /** 免登录分享回看：token 查询无租户上下文，跳过租户插件（结果以 share.tenant_id 还原租户身份）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM conversation_share WHERE token = #{token} LIMIT 1")
    ConversationShare selectByToken(String token);
}
