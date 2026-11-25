package com.pethelp.message.service.impl;

import com.pethelp.common.exception.BadRequestException;
import com.pethelp.common.exception.ForbiddenException;
import com.pethelp.common.exception.NotFoundException;
import com.pethelp.message.dto.MessageResponse;
import com.pethelp.message.dto.SendMessageRequest;
import com.pethelp.message.entity.Message;
import com.pethelp.message.mapper.ConversationMapper;
import com.pethelp.message.mapper.ConversationParticipantMapper;
import com.pethelp.message.mapper.MessageMapper;
import com.pethelp.message.service.ConversationService;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class ConversationServiceImpl implements ConversationService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_CONTENT_LENGTH = 2000;

    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final ConversationParticipantMapper conversationParticipantMapper;

    public ConversationServiceImpl(MessageMapper messageMapper,
                                   ConversationMapper conversationMapper,
                                   ConversationParticipantMapper conversationParticipantMapper) {
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.conversationParticipantMapper = conversationParticipantMapper;
    }

    @Override
    public List<MessageResponse> listMessages(Long currentUserId, Long conversationId,
                                              Long beforeId, Long afterId, Integer limit) {
        requireConversationAndParticipant(currentUserId, conversationId);
        int n = limit == null ? DEFAULT_LIMIT : limit;

        List<Message> messages;
        if (afterId != null) {
            messages = messageMapper.findAfter(conversationId, afterId, n);
        } else if (beforeId != null) {
            messages = messageMapper.findBefore(conversationId, beforeId, n);
        } else {
            messages = messageMapper.findLatest(conversationId, n);
        }

        return messages.stream()
                .sorted(Comparator.comparing(Message::getId))
                .map(this::toResponse)
                .toList();
    }

    @Override
    public MessageResponse sendMessage(Long currentUserId, Long conversationId, SendMessageRequest request) {
        requireConversationAndParticipant(currentUserId, conversationId);

        String content = request.content();
        if (content == null || content.isBlank()) {
            throw new BadRequestException("消息内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new BadRequestException("消息内容过长");
        }

        Message message = new Message();
        message.setConversationId(conversationId);
        message.setSenderId(currentUserId);
        message.setContent(content);
        messageMapper.insert(message);
        return toResponse(message);
    }

    private void requireConversationAndParticipant(Long currentUserId, Long conversationId) {
        if (conversationMapper.findById(conversationId) == null) {
            throw new NotFoundException("会话不存在");
        }
        if (conversationParticipantMapper.exists(conversationId, currentUserId) == 0) {
            throw new ForbiddenException("无权访问该会话");
        }
    }

    private MessageResponse toResponse(Message message) {
        return new MessageResponse(message.getId(), message.getConversationId(), message.getSenderId(),
                message.getContent(), message.getCreatedAt());
    }
}
