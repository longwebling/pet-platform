package com.pethelp.message.mapper;

import com.pethelp.message.entity.ConversationParticipant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationParticipantMapper {

    int exists(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    Long findLastReadMessageId(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    int insert(ConversationParticipant participant);
}
