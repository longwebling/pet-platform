package com.pethelp.pet;

import com.pethelp.pet.entity.Pet;
import com.pethelp.pet.entity.PetAuthorization;
import com.pethelp.pet.mapper.PetAuthorizationMapper;
import com.pethelp.pet.mapper.PetMapper;
import com.pethelp.pet.service.DispositionAuthorizationService;
import com.pethelp.pet.service.impl.DispositionAuthorizationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DispositionAuthorizationServiceImplTest {

    private PetMapper petMapper;
    private PetAuthorizationMapper petAuthorizationMapper;
    private DispositionAuthorizationService service;

    @BeforeEach
    void setUp() {
        petMapper = mock(PetMapper.class);
        petAuthorizationMapper = mock(PetAuthorizationMapper.class);
        service = new DispositionAuthorizationServiceImpl(petMapper, petAuthorizationMapper);
    }

    @Test
    void canApproveAdoption_ownerSelf_returnsTrue() {
        Pet pet = new Pet();
        pet.setOwnerId(5L);
        when(petMapper.findById(1L)).thenReturn(pet);

        assertThat(service.canApproveAdoption(1L, 5L)).isTrue();
        verify(petAuthorizationMapper, never()).findActiveByPetAndGranteeAndType(1L, 5L, "ADOPTION_APPROVAL");
    }

    @Test
    void canApproveAdoption_activeGrant_returnsTrue() {
        Pet pet = new Pet();
        pet.setOwnerId(9L);
        when(petMapper.findById(1L)).thenReturn(pet);
        when(petAuthorizationMapper.findActiveByPetAndGranteeAndType(1L, 5L, "ADOPTION_APPROVAL"))
                .thenReturn(new PetAuthorization());

        assertThat(service.canApproveAdoption(1L, 5L)).isTrue();
    }

    @Test
    void canApproveAdoption_noGrant_returnsFalse() {
        Pet pet = new Pet();
        pet.setOwnerId(9L);
        when(petMapper.findById(1L)).thenReturn(pet);
        when(petAuthorizationMapper.findActiveByPetAndGranteeAndType(1L, 5L, "ADOPTION_APPROVAL"))
                .thenReturn(null);

        assertThat(service.canApproveAdoption(1L, 5L)).isFalse();
    }

    @Test
    void canPublishAdoption_ownerSelf_returnsTrue() {
        Pet pet = new Pet();
        pet.setOwnerId(5L);
        when(petMapper.findById(1L)).thenReturn(pet);

        assertThat(service.canPublishAdoption(1L, 5L)).isTrue();
        verify(petAuthorizationMapper, never()).findActiveByPetAndGranteeAndType(1L, 5L, "PUBLISH");
    }

    @Test
    void canPublishAdoption_activePubGrant_returnsTrue() {
        Pet pet = new Pet();
        pet.setOwnerId(9L);
        when(petMapper.findById(1L)).thenReturn(pet);
        when(petAuthorizationMapper.findActiveByPetAndGranteeAndType(1L, 5L, "PUBLISH"))
                .thenReturn(new PetAuthorization());

        assertThat(service.canPublishAdoption(1L, 5L)).isTrue();
    }

    @Test
    void canPublishAdoption_noGrant_returnsFalse() {
        Pet pet = new Pet();
        pet.setOwnerId(9L);
        when(petMapper.findById(1L)).thenReturn(pet);
        when(petAuthorizationMapper.findActiveByPetAndGranteeAndType(1L, 5L, "PUBLISH"))
                .thenReturn(null);

        assertThat(service.canPublishAdoption(1L, 5L)).isFalse();
    }
}
