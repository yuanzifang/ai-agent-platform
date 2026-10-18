package com.platform.system.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.system.entity.MfaRecoveryCode;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MfaRecoveryCodeMapper extends BaseMapper<MfaRecoveryCode> {

    /** 登录第二步无 JWT 租户上下文，按 user_id 直接查询（恢复码仅归属用户）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT * FROM mfa_recovery_code
            WHERE user_id = #{userId} AND code_hash = #{codeHash} AND used = 0
            LIMIT 1
            """)
    MfaRecoveryCode findUnused(@Param("userId") Long userId, @Param("codeHash") String codeHash);

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(*) FROM mfa_recovery_code WHERE user_id = #{userId} AND used = 0")
    long countUnused(@Param("userId") Long userId);

    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE mfa_recovery_code SET used = 1 WHERE id = #{id}")
    int markUsed(@Param("id") Long id);

    @InterceptorIgnore(tenantLine = "true")
    @Delete("DELETE FROM mfa_recovery_code WHERE user_id = #{userId}")
    int deleteByUser(@Param("userId") Long userId);
}
