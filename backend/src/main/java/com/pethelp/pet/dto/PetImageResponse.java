package com.pethelp.pet.dto;

public record PetImageResponse(Long id, Long petId, String fileType, String reviewStatus, String signedUrl) {
}
