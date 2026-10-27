package com.platform.i18n.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.i18n.entity.I18nMessage;
import org.apache.ibatis.annotations.Mapper;

/** 全局表：i18n 资源不分区租户（跨租户共享）。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface I18nMessageMapper extends BaseMapper<I18nMessage> {
}
