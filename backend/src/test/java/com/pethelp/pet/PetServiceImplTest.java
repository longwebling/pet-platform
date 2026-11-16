package com.pethelp.pet;

import com.pethelp.common.exception.BadRequestException;
import com.pethelp.common.exception.NotFoundException;
import com.pethelp.pet.dto.CreatePetRequest;
import com.pethelp.pet.dto.PetResponse;
import com.pethelp.pet.entity.Pet;
import com.pethelp.pet.mapper.PetMapper;
import com.pethelp.pet.service.PetService;
import com.pethelp.pet.service.impl.PetServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PetServiceImplTest {

    private PetMapper petMapper;
    private PetService service;

    @BeforeEach
    void setUp() {
        petMapper = mock(PetMapper.class);
        service = new PetServiceImpl(petMapper);
    }

    @Test
    void create_fixedOwnedFields_insertsDraft() {
        doAnswer(inv -> {
            Pet p = inv.getArgument(0);
            p.setId(1L);
            return 1;
        }).when(petMapper).insert(any(Pet.class));

        Pet saved = new Pet();
        saved.setId(1L);
        saved.setStatus("DRAFT");
        saved.setSourceType("OWNED");
        saved.setOwnershipStatus("OWNED");
        saved.setAuthorizationStatus("SELF");
        saved.setSpecies("CAT");
        when(petMapper.findById(1L)).thenReturn(saved);

        PetResponse response = service.create(5L, new CreatePetRequest(
                "CAT", "中华田园猫", "2岁", "FEMALE", true, true, "健康", null, null, "北京", "朝阳区"));

        assertThat(response.status()).isEqualTo("DRAFT");
        assertThat(response.sourceType()).isEqualTo("OWNED");
        assertThat(response.ownershipStatus()).isEqualTo("OWNED");
        assertThat(response.authorizationStatus()).isEqualTo("SELF");

        ArgumentCaptor<Pet> captor = ArgumentCaptor.forClass(Pet.class);
        verify(petMapper).insert(captor.capture());
        Pet inserted = captor.getValue();
        assertThat(inserted.getPublisherId()).isEqualTo(5L);
        assertThat(inserted.getOwnerId()).isEqualTo(5L);
        assertThat(inserted.getCurrentCustodianId()).isEqualTo(5L);
        assertThat(inserted.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void create_blankSpecies_throws400() {
        CreatePetRequest request = new CreatePetRequest(
                "", null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(5L, request))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void create_onlyLng_throws400() {
        CreatePetRequest request = new CreatePetRequest(
                "CAT", null, null, null, null, null, null, 116.397, null, null, null);

        assertThatThrownBy(() -> service.create(5L, request))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void create_lngOutOfRange_throws400() {
        CreatePetRequest request = new CreatePetRequest(
                "CAT", null, null, null, null, null, null, 181.0, 39.908, null, null);

        assertThatThrownBy(() -> service.create(5L, request))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void getById_notFound_throws404() {
        when(petMapper.findByIdActive(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.getById(99L))
                .isInstanceOf(NotFoundException.class);
    }
}
