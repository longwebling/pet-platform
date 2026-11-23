package com.pethelp.pet.dto;

public record LocationGrantResponse(Long id, Long petId, Long granteeId, String locationType, String status) {
}
