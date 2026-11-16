package com.pethelp.pet;

import com.pethelp.common.exception.BadRequestException;
import com.pethelp.common.exception.ConflictException;
import com.pethelp.common.exception.ForbiddenException;
import com.pethelp.common.exception.NotFoundException;
import com.pethelp.pet.dto.CreatePetRequest;
import com.pethelp.pet.dto.PetResponse;
import com.pethelp.pet.dto.UpdatePetRequest;
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

    @Test
    void update_notOwner_throws403() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setPublisherId(99L);
        pet.setStatus("DRAFT");
        when(petMapper.findById(1L)).thenReturn(pet);

        UpdatePetRequest request = new UpdatePetRequest(
                "CAT", null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(1L, 5L, request))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void update_wrongStatus_throws409() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setPublisherId(5L);
        pet.setStatus("ARCHIVED");
        when(petMapper.findById(1L)).thenReturn(pet);

        UpdatePetRequest request = new UpdatePetRequest(
                "CAT", null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(1L, 5L, request))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void update_fullReplace_returnsUpdated() {
        Pet existing = new Pet();
        existing.setId(1L);
        existing.setPublisherId(5L);
        existing.setStatus("DRAFT");
        existing.setSpecies("CAT");

        Pet updated = new Pet();
        updated.setId(1L);
        updated.setPublisherId(5L);
        updated.setStatus("DRAFT");
        updated.setSpecies("DOG");
        updated.setBreed("柯基");
        updated.setSourceType("OWNED");
        updated.setOwnershipStatus("OWNED");
        updated.setAuthorizationStatus("SELF");

        when(petMapper.findById(1L)).thenReturn(existing, updated);
        when(petMapper.update(any(Pet.class))).thenReturn(1);

        PetResponse response = service.update(1L, 5L, new UpdatePetRequest(
                "DOG", "柯基", "3岁", null, null, null, null, null, null, null, null));

        assertThat(response.species()).isEqualTo("DOG");
        assertThat(response.breed()).isEqualTo("柯基");

        ArgumentCaptor<Pet> captor = ArgumentCaptor.forClass(Pet.class);
        verify(petMapper).update(captor.capture());
        assertThat(captor.getValue().getSpecies()).isEqualTo("DOG");
        assertThat(captor.getValue().getBreed()).isEqualTo("柯基");
    }

    @Test
    void publish_notOwner_throws403() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setPublisherId(99L);
        pet.setStatus("DRAFT");
        when(petMapper.findById(1L)).thenReturn(pet);

        assertThatThrownBy(() -> service.publish(1L, 5L))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void publish_notDraft_throws409() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setPublisherId(5L);
        pet.setStatus("ACTIVE");
        when(petMapper.findById(1L)).thenReturn(pet);

        assertThatThrownBy(() -> service.publish(1L, 5L))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void publish_success_returnsActive() {
        Pet draft = new Pet();
        draft.setId(1L);
        draft.setPublisherId(5L);
        draft.setStatus("DRAFT");
        draft.setSpecies("CAT");

        Pet active = new Pet();
        active.setId(1L);
        active.setPublisherId(5L);
        active.setStatus("ACTIVE");
        active.setSpecies("CAT");
        active.setSourceType("OWNED");
        active.setOwnershipStatus("OWNED");
        active.setAuthorizationStatus("SELF");

        when(petMapper.findById(1L)).thenReturn(draft, active);
        when(petMapper.publish(1L, 5L)).thenReturn(1);

        PetResponse response = service.publish(1L, 5L);

        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    void archive_notActive_throws409() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setPublisherId(5L);
        pet.setStatus("DRAFT");
        when(petMapper.findById(1L)).thenReturn(pet);

        assertThatThrownBy(() -> service.archive(1L, 5L))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void delete_success_softDeletes() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setPublisherId(5L);
        pet.setStatus("ACTIVE");
        when(petMapper.findById(1L)).thenReturn(pet);
        when(petMapper.softDelete(1L, 5L)).thenReturn(1);

        service.delete(1L, 5L);

        verify(petMapper).softDelete(1L, 5L);
    }

    @Test
    void update_onDeleted_throws404() {
        when(petMapper.findById(1L)).thenReturn(null);

        UpdatePetRequest request = new UpdatePetRequest(
                "CAT", null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(1L, 5L, request))
                .isInstanceOf(NotFoundException.class);
    }
}
