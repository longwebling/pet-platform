package com.pethelp.pet.service;

import com.pethelp.pet.dto.CreatePetRequest;
import com.pethelp.pet.dto.PetResponse;
import com.pethelp.pet.dto.UpdatePetRequest;

public interface PetService {

    PetResponse create(Long currentUserId, CreatePetRequest request);

    PetResponse getById(Long petId);

    PetResponse update(Long petId, Long currentUserId, UpdatePetRequest request);

    PetResponse publish(Long petId, Long currentUserId);

    PetResponse archive(Long petId, Long currentUserId);

    void delete(Long petId, Long currentUserId);

    void transferOwnershipToAdopter(Long petId, Long adopterId, Long arrangementId, Long operatorId);
}
