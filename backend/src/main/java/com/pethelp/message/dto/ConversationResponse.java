package com.pethelp.message.dto;

import java.time.OffsetDateTime;

public record ConversationResponse(Long id, Long postId, ParticipantSummary peer,
                                  MessageResponse lastMessage, long unreadCount,
                                  Long firstUnreadMessageId, OffsetDateTime lastMessageAt) {
}
