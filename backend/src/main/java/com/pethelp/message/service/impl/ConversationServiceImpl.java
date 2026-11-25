package com.pethelp.message.service.impl;

import com.pethelp.common.exception.BadRequestException;
import com.pethelp.common.exception.ForbiddenException;
import com.pethelp.common.exception.NotFoundException;
import com.pethelp.message.dto.ConversationListItem;
import com.pethelp.message.dto.ConversationResponse;
import com.pethelp.message.dto.CreateConversationRequest;
import com.pethelp.message.dto.MessageResponse;
import com.pethelp.message.dto.ParticipantSummary;
import com.pethelp.message.dto.SendMessageRequest;
import com.pethelp.message.entity.Conversation;
import com.pethelp.message.entity.ConversationParticipant;
import com.pethelp.message.entity.Message;
import com.pethelp.message.mapper.ConversationMapper;
import com.pethelp.message.mapper.ConversationParticipantMapper;
import com.pethelp.message.mapper.MessageMapper;
import com.pethelp.message.service.ConversationService;
import com.pethelp.post.entity.PetPost;
import com.pethelp.post.mapper.PostMapper;
import com.pethelp.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

@Service
public class ConversationServiceImpl implements ConversationService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_CONTENT_LENGTH = 2000;

    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final ConversationParticipantMapper conversationParticipantMapper;
    private final PostMapper postMapper;
    private final UserMapper userMapper;

    public ConversationServiceImpl(MessageMapper messageMapper,
                                   ConversationMapper conversationMapper,
                                   ConversationParticipantMapper conversationParticipantMapper,
                                   PostMapper postMapper,
                                   UserMapper userMapper) {
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.conversationParticipantMapper = conversationParticipantMapper;
        this.postMapper = postMapper;
        this.userMapper = userMapper;
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

    @Override
    public List<ConversationResponse> listConversations(Long currentUserId, int page, int size) {
        int offset = (page - 1) * size;
        return conversationMapper.listByUser(currentUserId, offset, size).stream()
                .map(item -> toConversationResponse(item, currentUserId))
                .toList();
    }

    @Override
    @Transactional
    public ConversationResponse createConversation(Long currentUserId, CreateConversationRequest request) {
        Long postId = request.postId();
        Long peerUserId = request.peerUserId();

        PetPost post = postMapper.findById(postId);
        if (post == null) {
            throw new NotFoundException("帖子不存在");
        }
        if (userMapper.findById(peerUserId) == null) {
            throw new NotFoundException("用户不存在");
        }
        if (peerUserId.equals(currentUserId)) {
            throw new BadRequestException("不能给自己发消息");
        }
        if (!currentUserId.equals(post.getPublisherId()) && !peerUserId.equals(post.getPublisherId())) {
            throw new ForbiddenException("至少一方须为帖子发布者");
        }

        Long existingId = conversationMapper.findExistingIdByPostAndPair(postId, currentUserId, peerUserId);
        if (existingId != null) {
            return buildSummary(existingId, currentUserId);
        }

        Conversation conversation = new Conversation();
        conversation.setPostId(postId);
        conversationMapper.insert(conversation);

        insertParticipant(conversation.getId(), currentUserId);
        insertParticipant(conversation.getId(), peerUserId);

        return buildSummary(conversation.getId(), currentUserId);
    }

    ConversationResponse buildSummary(Long conversationId, Long currentUserId) {
        ConversationListItem item = conversationMapper.findByIdWithPeer(conversationId, currentUserId);
        if (item == null) {
            throw new NotFoundException("会话不存在");
        }
        return toConversationResponse(item, currentUserId);
    }

    private void insertParticipant(Long conversationId, Long userId) {
        ConversationParticipant participant = new ConversationParticipant();
        participant.setConversationId(conversationId);
        participant.setUserId(userId);
        conversationParticipantMapper.insert(participant);
    }

    private ConversationResponse toConversationResponse(ConversationListItem item, Long me) {
        Long conversationId = item.getConversationId();
        Message lastMessage = messageMapper.findLastMessage(conversationId);
        Long lastReadMessageId = conversationParticipantMapper.findLastReadMessageId(conversationId, me);
        long unreadCount = messageMapper.countUnread(conversationId, me, lastReadMessageId);
        Long firstUnreadId = messageMapper.findFirstUnreadId(conversationId, me, lastReadMessageId);

        ParticipantSummary peer = new ParticipantSummary(item.getPeerId(), item.getPeerNickname(), item.getPeerAvatar());
        MessageResponse lastMessageResponse = lastMessage == null ? null : toResponse(lastMessage);
        OffsetDateTime lastMessageAt = lastMessage == null ? null : lastMessage.getCreatedAt();
        return new ConversationResponse(conversationId, item.getPostId(), peer, lastMessageResponse,
                unreadCount, firstUnreadId, lastMessageAt);
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
