package com.pethelp.message.mapper;

import com.pethelp.message.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MessageMapper {

    int insert(Message message);

    Message findById(@Param("id") Long id);

    List<Message> findLatest(@Param("conversationId") Long conversationId, @Param("limit") int limit);

    List<Message> findBefore(@Param("conversationId") Long conversationId, @Param("beforeId") Long beforeId,
                             @Param("limit") int limit);

    List<Message> findAfter(@Param("conversationId") Long conversationId, @Param("afterId") Long afterId,
                            @Param("limit") int limit);

    Message findLastMessage(@Param("conversationId") Long conversationId);

    long countUnread(@Param("conversationId") Long conversationId, @Param("userId") Long userId,
                     @Param("lastReadMessageId") Long lastReadMessageId);

    Long findFirstUnreadId(@Param("conversationId") Long conversationId, @Param("userId") Long userId,
                           @Param("lastReadMessageId") Long lastReadMessageId);
}
