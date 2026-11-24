package com.pethelp.application.dto;

public record ApproveResponse(Long arrangementId, String postStatus, int rejectedOthers) {
}
