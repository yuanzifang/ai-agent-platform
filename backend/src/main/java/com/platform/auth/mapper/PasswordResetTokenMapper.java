package com.platform.auth.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.auth.entity.PasswordResetToken;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PasswordResetTokenMapper extends BaseMapper<PasswordResetToken> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM password_reset_token WHERE token_hash = #{hash} LIMIT 1")
    PasswordResetToken findByHashIgnore(@Param("hash") String hash);

    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE password_reset_token SET used = 1 WHERE id = #{id}")
    int markUsedIgnore(@Param("id") Long id);
}
