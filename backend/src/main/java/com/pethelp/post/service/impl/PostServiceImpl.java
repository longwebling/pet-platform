package com.pethelp.post.service.impl;

import com.pethelp.common.exception.BadRequestException;
import com.pethelp.common.exception.ConflictException;
import com.pethelp.common.exception.ForbiddenException;
import com.pethelp.common.exception.NotFoundException;
import com.pethelp.pet.entity.Pet;
import com.pethelp.pet.mapper.PetMapper;
import com.pethelp.pet.service.DispositionAuthorizationService;
import com.pethelp.post.dto.CreatePostRequest;
import com.pethelp.post.dto.PostResponse;
import com.pethelp.post.dto.UpdatePostRequest;
import com.pethelp.post.entity.PetPost;
import com.pethelp.post.mapper.PostMapper;
import com.pethelp.post.service.PostService;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

@Service
public class PostServiceImpl implements PostService {

    private static final Set<String> POST_TYPES = Set.of("FINDING", "ADOPTION");

    private final PetMapper petMapper;
    private final PostMapper postMapper;
    private final DispositionAuthorizationService dispositionAuthorizationService;

    public PostServiceImpl(PetMapper petMapper, PostMapper postMapper,
                           DispositionAuthorizationService dispositionAuthorizationService) {
        this.petMapper = petMapper;
        this.postMapper = postMapper;
        this.dispositionAuthorizationService = dispositionAuthorizationService;
    }

    @Override
    public PostResponse create(Long currentUserId, CreatePostRequest request) {
        Pet pet = petMapper.findById(request.petId());
        if (pet == null) {
            throw new NotFoundException("宠物不存在");
        }
        if (!"ACTIVE".equals(pet.getStatus())) {
            throw new ConflictException("仅已发布的宠物可创建帖子");
        }
        String postType = request.postType();
        if (postType == null || !POST_TYPES.contains(postType)) {
            throw new BadRequestException("帖子类型非法");
        }
        if (request.title() == null || request.title().isBlank()) {
            throw new BadRequestException("标题不能为空");
        }
        if ("ADOPTION".equals(postType)) {
            if (!dispositionAuthorizationService.canPublishAdoption(pet.getId(), currentUserId)) {
                throw new ForbiddenException("无权发布领养信息");
            }
        } else {
            boolean owner = (pet.getPublisherId() != null && pet.getPublisherId().equals(currentUserId))
                    || (pet.getOwnerId() != null && pet.getOwnerId().equals(currentUserId));
            if (!owner) {
                throw new ForbiddenException("无权发布寻宠信息");
            }
        }

        PetPost post = new PetPost();
        post.setPetId(pet.getId());
        post.setPublisherId(currentUserId);
        post.setPostType(postType);
        post.setTitle(request.title());
        post.setDescription(request.description());
        post.setStatus("OPEN");
        post.setVerifiedAt(OffsetDateTime.now());
        postMapper.insert(post);
        post.setSpecies(pet.getSpecies());
        post.setGeoHash(pet.getGeoHash());
        post.setCity(pet.getCity());
        post.setDistrict(pet.getDistrict());
        return toResponse(post);
    }

    @Override
    public PostResponse getById(Long postId) {
        PetPost post = postMapper.findById(postId);
        if (post == null) {
            throw new NotFoundException("帖子不存在");
        }
        return toResponse(post);
    }

    @Override
    public List<PostResponse> list(String postType, String species, int page, int size) {
        int offset = (page - 1) * size;
        return postMapper.list(postType, species, offset, size).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public PostResponse update(Long postId, Long currentUserId, UpdatePostRequest request) {
        PetPost post = postMapper.findById(postId);
        if (post == null) {
            throw new NotFoundException("帖子不存在");
        }
        if (!post.getPublisherId().equals(currentUserId)) {
            throw new ForbiddenException("仅发布者可操作");
        }
        if (!"OPEN".equals(post.getStatus())) {
            throw new ConflictException("仅开放中的帖子可编辑");
        }
        if (postMapper.update(postId, currentUserId, request.title(), request.description()) == 0) {
            throw new ConflictException("仅开放中的帖子可编辑");
        }
        return toResponse(postMapper.findById(postId));
    }

    @Override
    public PostResponse close(Long postId, Long currentUserId) {
        PetPost post = postMapper.findById(postId);
        if (post == null) {
            throw new NotFoundException("帖子不存在");
        }
        if (!post.getPublisherId().equals(currentUserId)) {
            throw new ForbiddenException("仅发布者可操作");
        }
        if (!("OPEN".equals(post.getStatus()) || "RESERVED".equals(post.getStatus()))) {
            throw new ConflictException("当前状态不可关闭");
        }
        if (postMapper.close(postId, currentUserId) == 0) {
            throw new ConflictException("当前状态不可关闭");
        }
        return toResponse(postMapper.findById(postId));
    }

    @Override
    public PostResponse cancel(Long postId, Long currentUserId) {
        PetPost post = postMapper.findById(postId);
        if (post == null) {
            throw new NotFoundException("帖子不存在");
        }
        if (!post.getPublisherId().equals(currentUserId)) {
            throw new ForbiddenException("仅发布者可操作");
        }
        if (!("OPEN".equals(post.getStatus()) || "RESERVED".equals(post.getStatus()))) {
            throw new ConflictException("当前状态不可取消");
        }
        if (postMapper.cancel(postId, currentUserId) == 0) {
            throw new ConflictException("当前状态不可取消");
        }
        return toResponse(postMapper.findById(postId));
    }

    @Override
    public PostResponse convertToAdoption(Long postId, Long currentUserId) {
        PetPost post = postMapper.findById(postId);
        if (post == null) {
            throw new NotFoundException("帖子不存在");
        }
        if (!"FINDING".equals(post.getPostType()) || !"OPEN".equals(post.getStatus())) {
            throw new ConflictException("仅开放中的寻宠帖子可转领养");
        }
        if (!post.getPublisherId().equals(currentUserId)) {
            throw new ForbiddenException("仅发布者可操作");
        }
        Pet pet = petMapper.findById(post.getPetId());
        if (pet == null
                || !"UNOWNED".equals(pet.getOwnershipStatus())
                || !"AUTHORIZED".equals(pet.getAuthorizationStatus())) {
            throw new ConflictException("宠物需为无主且已授权方可转领养");
        }
        // v0.1：转领养为校验-only，满足条件也恒拒
        throw new ConflictException("转领养功能暂未开放");
    }

    private PostResponse toResponse(PetPost post) {
        return new PostResponse(
                post.getId(), post.getPetId(), post.getPublisherId(), post.getPostType(),
                post.getTitle(), post.getDescription(), post.getStatus(),
                post.getSpecies(), post.getGeoHash(), post.getCity(), post.getDistrict());
    }
}
