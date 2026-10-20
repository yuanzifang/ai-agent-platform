package com.platform.system.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.system.entity.AuditChainCheckpoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AuditChainCheckpointMapper extends BaseMapper<AuditChainCheckpoint> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM audit_chain_checkpoint WHERE tenant_id = #{tenantId} " +
            "ORDER BY partition_name DESC")
    List<AuditChainCheckpoint> listByTenant(@Param("tenantId") Long tenantId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT partition_name FROM information_schema.partitions " +
            "WHERE table_schema = DATABASE() AND table_name = 'audit_operation' " +
            "AND partition_name IS NOT NULL")
    List<String> activeAuditPartitions();
}
