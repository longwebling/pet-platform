package com.pethelp.pet.service.impl;

import com.pethelp.common.exception.BadRequestException;
import com.pethelp.common.exception.ConflictException;
import com.pethelp.common.exception.ForbiddenException;
import com.pethelp.common.exception.NotFoundException;
import com.pethelp.pet.dto.PetImageResponse;
import com.pethelp.pet.entity.Pet;
import com.pethelp.pet.entity.PetImage;
import com.pethelp.pet.mapper.PetImageMapper;
import com.pethelp.pet.mapper.PetMapper;
import com.pethelp.pet.service.PetImageService;
import com.pethelp.picture.review.ImageReviewService;
import com.pethelp.picture.storage.ObjectStorage;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class PetImageServiceImpl implements PetImageService {

    private static final int MAX_IMAGES = 9;
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final Set<String> EDITABLE = Set.of("DRAFT", "ACTIVE");

    private static final byte[] PNG_SIG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_SIG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private enum ImageType {
        PNG("png", "image/png"), JPG("jpg", "image/jpeg");
        final String extension;
        final String contentType;

        ImageType(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }
    }

    private final PetMapper petMapper;
    private final PetImageMapper petImageMapper;
    private final ObjectStorage objectStorage;
    private final ImageReviewService imageReviewService;

    public PetImageServiceImpl(PetMapper petMapper, PetImageMapper petImageMapper,
                               ObjectStorage objectStorage, ImageReviewService imageReviewService) {
        this.petMapper = petMapper;
        this.petImageMapper = petImageMapper;
        this.objectStorage = objectStorage;
        this.imageReviewService = imageReviewService;
    }

    @Override
    public PetImageResponse upload(Long petId, Long currentUserId, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BadRequestException("文件为空");
        }
        if (content.length > MAX_BYTES) {
            throw new BadRequestException("文件过大");
        }
        ImageType type = sniff(content);

        Pet pet = petMapper.findById(petId);
        if (pet == null) {
            throw new NotFoundException("宠物不存在");
        }
        if (!pet.getPublisherId().equals(currentUserId)) {
            throw new ForbiddenException("仅档案发布者可操作");
        }
        if (!EDITABLE.contains(pet.getStatus())) {
            throw new ConflictException("当前状态不可上传");
        }
        if (petImageMapper.countByPetIdAndType(petId, "PHOTO") >= MAX_IMAGES) {
            throw new BadRequestException("图片数量已达上限");
        }

        String fileKey = "pets/" + petId + "/" + UUID.randomUUID() + "." + type.extension;
        String reviewStatus = imageReviewService.review(content, fileKey);
        objectStorage.put(fileKey, content, type.contentType);

        PetImage image = new PetImage();
        image.setPetId(petId);
        image.setUploaderId(currentUserId);
        image.setFileKey(fileKey);
        image.setFileType("PHOTO");
        image.setReviewStatus(reviewStatus);
        petImageMapper.insert(image);
        return new PetImageResponse(image.getId(), petId, "PHOTO", reviewStatus, null);
    }

    @Override
    public List<PetImageResponse> list(Long petId) {
        Pet pet = petMapper.findByIdActive(petId);
        if (pet == null) {
            throw new NotFoundException("宠物不存在");
        }
        return petImageMapper.findByPetIdPublicApproved(petId).stream()
                .map(img -> new PetImageResponse(
                        img.getId(), img.getPetId(), img.getFileType(), img.getReviewStatus(),
                        objectStorage.signedUrl(img.getFileKey(), Duration.ofHours(1))))
                .toList();
    }

    private ImageType sniff(byte[] content) {
        if (startsWith(content, PNG_SIG)) {
            return ImageType.PNG;
        }
        if (startsWith(content, JPEG_SIG)) {
            return ImageType.JPG;
        }
        throw new BadRequestException("仅支持 JPG/PNG");
    }

    private boolean startsWith(byte[] content, byte[] sig) {
        if (content.length < sig.length) {
            return false;
        }
        for (int i = 0; i < sig.length; i++) {
            if (content[i] != sig[i]) {
                return false;
            }
        }
        return true;
    }
}
