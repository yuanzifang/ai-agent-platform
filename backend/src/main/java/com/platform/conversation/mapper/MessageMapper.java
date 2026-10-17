package com.platform.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.conversation.entity.Message;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
