package com.pethelp.application.dto;

public record ApplicationResponse(Long id, Long postId, Long adopterId, String message, String status, String rejectReason) {
}
