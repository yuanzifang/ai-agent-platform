package com.platform.agent.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.agent.entity.TestCaseRunItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface TestCaseRunItemMapper extends BaseMapper<TestCaseRunItem> {
}
