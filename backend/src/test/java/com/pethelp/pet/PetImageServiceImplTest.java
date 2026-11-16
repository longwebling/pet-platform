package com.pethelp.pet;

import com.pethelp.common.exception.BadRequestException;
import com.pethelp.common.exception.ConflictException;
import com.pethelp.common.exception.ForbiddenException;
import com.pethelp.common.exception.NotFoundException;
import com.pethelp.pet.dto.PetImageResponse;
import com.pethelp.pet.entity.Pet;
import com.pethelp.pet.entity.PetImage;
import com.pethelp.pet.mapper.PetImageMapper;
import com.pethelp.pet.mapper.PetMapper;
import com.pethelp.pet.service.impl.PetImageServiceImpl;
import com.pethelp.picture.review.ImageReviewService;
import com.pethelp.picture.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PetImageServiceImplTest {

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private PetMapper petMapper;
    private PetImageMapper petImageMapper;
    private ObjectStorage objectStorage;
    private ImageReviewService imageReviewService;
    private PetImageServiceImpl service;

    @BeforeEach
    void setUp() {
        petMapper = mock(PetMapper.class);
        petImageMapper = mock(PetImageMapper.class);
        objectStorage = mock(ObjectStorage.class);
        imageReviewService = mock(ImageReviewService.class);
        service = new PetImageServiceImpl(petMapper, petImageMapper, objectStorage, imageReviewService);
    }

    @Test
    void upload_validPng_storesAndInsertsApproved() {
        when(petMapper.findById(1L)).thenReturn(pet(5L, "DRAFT"));
        when(petImageMapper.countByPetIdAndType(1L, "PHOTO")).thenReturn(0);
        when(imageReviewService.review(any(byte[].class), anyString())).thenReturn("APPROVED");
        doAnswer(inv -> {
            PetImage img = inv.getArgument(0);
            img.setId(7L);
            return 1;
        }).when(petImageMapper).insert(any(PetImage.class));

        PetImageResponse response = service.upload(1L, 5L, PNG);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.petId()).isEqualTo(1L);
        assertThat(response.fileType()).isEqualTo("PHOTO");
        assertThat(response.reviewStatus()).isEqualTo("APPROVED");
        assertThat(response.signedUrl()).isNull();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(objectStorage).put(keyCaptor.capture(), eq(PNG), eq("image/png"));
        assertThat(keyCaptor.getValue()).startsWith("pets/1/").endsWith(".png");

        ArgumentCaptor<PetImage> imgCaptor = ArgumentCaptor.forClass(PetImage.class);
        verify(petImageMapper).insert(imgCaptor.capture());
        PetImage inserted = imgCaptor.getValue();
        assertThat(inserted.getUploaderId()).isEqualTo(5L);
        assertThat(inserted.getReviewStatus()).isEqualTo("APPROVED");
        assertThat(inserted.getFileType()).isEqualTo("PHOTO");
    }

    @Test
    void upload_emptyContent_throws400() {
        when(petMapper.findById(1L)).thenReturn(pet(5L, "DRAFT"));

        assertThatThrownBy(() -> service.upload(1L, 5L, new byte[0]))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void upload_oversize_throws400() {
        when(petMapper.findById(1L)).thenReturn(pet(5L, "DRAFT"));

        assertThatThrownBy(() -> service.upload(1L, 5L, new byte[10 * 1024 * 1024 + 1]))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void upload_unsupportedFormat_throws400() {
        when(petMapper.findById(1L)).thenReturn(pet(5L, "DRAFT"));

        assertThatThrownBy(() -> service.upload(1L, 5L, "hello".getBytes()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void upload_notOwner_throws403() {
        when(petMapper.findById(1L)).thenReturn(pet(99L, "DRAFT"));

        assertThatThrownBy(() -> service.upload(1L, 5L, PNG))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void upload_petNotFound_throws404() {
        when(petMapper.findById(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.upload(1L, 5L, PNG))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void upload_archivedPet_throws409() {
        when(petMapper.findById(1L)).thenReturn(pet(5L, "ARCHIVED"));

        assertThatThrownBy(() -> service.upload(1L, 5L, PNG))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void upload_limitReached_throws400() {
        when(petMapper.findById(1L)).thenReturn(pet(5L, "DRAFT"));
        when(petImageMapper.countByPetIdAndType(1L, "PHOTO")).thenReturn(9);

        assertThatThrownBy(() -> service.upload(1L, 5L, PNG))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void list_activePet_returnsSignedUrls() {
        when(petMapper.findByIdActive(1L)).thenReturn(pet(5L, "ACTIVE"));

        PetImage image = new PetImage();
        image.setId(7L);
        image.setPetId(1L);
        image.setFileKey("pets/1/a.jpg");
        image.setFileType("PHOTO");
        image.setReviewStatus("APPROVED");
        when(petImageMapper.findByPetIdPublicApproved(1L)).thenReturn(List.of(image));
        when(objectStorage.signedUrl("pets/1/a.jpg", Duration.ofHours(1)))
                .thenReturn("memory://pets/1/a.jpg");

        List<PetImageResponse> list = service.list(1L);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).signedUrl()).isEqualTo("memory://pets/1/a.jpg");
        assertThat(list.get(0).id()).isEqualTo(7L);
    }

    @Test
    void list_inactivePet_throws404() {
        when(petMapper.findByIdActive(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.list(1L))
                .isInstanceOf(NotFoundException.class);
    }

    private Pet pet(Long publisherId, String status) {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setPublisherId(publisherId);
        pet.setStatus(status);
        return pet;
    }
}
