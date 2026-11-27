package com.pethelp.admin.dto;

public record AdminImageResponse(Long id, Long petId, Long uploaderId, String fileType, String reviewStatus) {
}
