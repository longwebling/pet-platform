package com.pethelp.message.mapper;

import com.pethelp.message.dto.ConversationListItem;
import com.pethelp.message.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ConversationMapper {

    Conversation findById(@Param("id") Long id);

    List<ConversationListItem> listByUser(@Param("userId") Long userId, @Param("offset") int offset,
                                          @Param("limit") int limit);

    ConversationListItem findByIdWithPeer(@Param("conversationId") Long conversationId, @Param("userId") Long userId);
}
