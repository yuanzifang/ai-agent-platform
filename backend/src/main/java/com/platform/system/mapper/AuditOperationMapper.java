package com.platform.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.system.entity.AuditOperation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditOperationMapper extends BaseMapper<AuditOperation> {
}
