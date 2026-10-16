package com.platform.auth.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.auth.entity.UserSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserSessionMapper extends BaseMapper<UserSession> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM user_session WHERE refresh_token_hash = #{hash} AND revoked = 0 LIMIT 1")
    UserSession findValidByHash(String hash);

    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE user_session SET revoked = 1 WHERE refresh_token_hash = #{hash}")
    int revokeByHash(String hash);
}
