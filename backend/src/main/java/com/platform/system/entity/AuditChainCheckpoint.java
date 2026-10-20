package com.platform.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 审计旧分区的链锚点；partition_name 对应已删除的分区。 */
@Data
@TableName("audit_chain_checkpoint")
public class AuditChainCheckpoint {
    private Long tenantId;
    private String partitionName;
    private Long lastRemovedId;
    private String lastRemovedHash;
    private LocalDateTime createdAt;
}
