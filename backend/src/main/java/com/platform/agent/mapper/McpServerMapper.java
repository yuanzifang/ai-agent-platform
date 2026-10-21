package com.platform.agent.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.agent.entity.McpServer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface McpServerMapper extends BaseMapper<McpServer> {

    /** 服务端通知 tools/list_changed；按 id 更新，避免后台线程没有租户上下文时被租户插件过滤。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE mcp_server SET tools_changed_at = NOW() WHERE id = #{id}")
    int markToolsChanged(@Param("id") Long id);

    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE mcp_server SET tools_changed_at = NULL WHERE id = #{id}")
    int clearToolsChanged(@Param("id") Long id);
}
