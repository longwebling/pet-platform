package com.pethelp.pet.service.impl;

import com.pethelp.pet.entity.Pet;
import com.pethelp.pet.mapper.PetAuthorizationMapper;
import com.pethelp.pet.mapper.PetMapper;
import com.pethelp.pet.service.DispositionAuthorizationService;
import org.springframework.stereotype.Service;

@Service
public class DispositionAuthorizationServiceImpl implements DispositionAuthorizationService {

    private final PetMapper petMapper;
    private final PetAuthorizationMapper petAuthorizationMapper;

    public DispositionAuthorizationServiceImpl(PetMapper petMapper, PetAuthorizationMapper petAuthorizationMapper) {
        this.petMapper = petMapper;
        this.petAuthorizationMapper = petAuthorizationMapper;
    }

    @Override
    public boolean canPublishAdoption(Long petId, Long userId) {
        Pet pet = petMapper.findById(petId);
        return pet != null && (userId.equals(pet.getOwnerId())
                || petAuthorizationMapper.findActiveByPetAndGranteeAndType(petId, userId, "PUBLISH") != null);
    }

    @Override
    public boolean canApproveAdoption(Long petId, Long userId) {
        Pet pet = petMapper.findById(petId);
        return pet != null && (userId.equals(pet.getOwnerId())
                || petAuthorizationMapper.findActiveByPetAndGranteeAndType(petId, userId, "ADOPTION_APPROVAL") != null);
    }
}
