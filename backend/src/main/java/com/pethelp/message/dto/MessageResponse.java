package com.pethelp.message.dto;

import java.time.OffsetDateTime;

public record MessageResponse(Long id, Long conversationId, Long senderId, String content,
                              OffsetDateTime createdAt) {
}
