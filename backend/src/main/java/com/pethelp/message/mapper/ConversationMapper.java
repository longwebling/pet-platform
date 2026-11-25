package com.pethelp.message.mapper;

import com.pethelp.message.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationMapper {

    Conversation findById(@Param("id") Long id);
}
