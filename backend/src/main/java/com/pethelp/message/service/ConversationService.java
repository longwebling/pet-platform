package com.pethelp.message.service;

import com.pethelp.message.dto.ConversationResponse;
import com.pethelp.message.dto.CreateConversationRequest;
import com.pethelp.message.dto.MessageResponse;
import com.pethelp.message.dto.SendMessageRequest;

import java.util.List;

public interface ConversationService {

    List<MessageResponse> listMessages(Long currentUserId, Long conversationId, Long beforeId, Long afterId, Integer limit);

    MessageResponse sendMessage(Long currentUserId, Long conversationId, SendMessageRequest request);

    List<ConversationResponse> listConversations(Long currentUserId, int page, int size);

    ConversationResponse createConversation(Long currentUserId, CreateConversationRequest request);

    void markConversationRead(Long currentUserId, Long conversationId);

    void markMessageRead(Long currentUserId, Long messageId);
}
