package com.pethelp.message.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationParticipantMapper {

    int exists(@Param("conversationId") Long conversationId, @Param("userId") Long userId);
}
