package com.pethelp.pet.service;

import com.pethelp.pet.dto.CreatePetRequest;
import com.pethelp.pet.dto.PetResponse;

public interface PetService {

    PetResponse create(Long currentUserId, CreatePetRequest request);

    PetResponse getById(Long petId);
}
