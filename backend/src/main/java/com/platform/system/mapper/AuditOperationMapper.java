package com.platform.system.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.system.entity.AuditOperation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuditOperationMapper extends BaseMapper<AuditOperation> {
    /** 租户级审计链行锁；多实例写入在同一事务内串行。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id FROM sys_tenant WHERE id = #{tenantId} FOR UPDATE")
    Long lockTenant(@Param("tenantId") Long tenantId);

    /** 读取并锁住链尾最新行（锁定读可绕过 REPEATABLE READ 旧快照；手动带 tenant_id）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM audit_operation WHERE tenant_id = #{tenantId} ORDER BY id DESC LIMIT 1 FOR UPDATE")
    AuditOperation lockLast(@Param("tenantId") Long tenantId);
}
