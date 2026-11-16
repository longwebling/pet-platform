package com.pethelp.pet.service;

import com.pethelp.pet.dto.PetImageResponse;

import java.util.List;

public interface PetImageService {

    PetImageResponse upload(Long petId, Long currentUserId, byte[] content);

    List<PetImageResponse> list(Long petId);
}
