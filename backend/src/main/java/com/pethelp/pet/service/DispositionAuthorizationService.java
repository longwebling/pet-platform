package com.pethelp.pet.service;

public interface DispositionAuthorizationService {

    boolean canPublishAdoption(Long petId, Long userId);

    boolean canApproveAdoption(Long petId, Long userId);
}
