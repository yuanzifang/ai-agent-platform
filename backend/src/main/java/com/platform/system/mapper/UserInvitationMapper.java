package com.platform.system.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.system.entity.UserInvitation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserInvitationMapper extends BaseMapper<UserInvitation> {

    /** 免登录激活入口：token 定位邀请（无租户上下文），随后以邀请所属租户执行后续写入。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM user_invitation WHERE token = #{token} LIMIT 1")
    UserInvitation findByToken(String token);
}
