package com.pethelp.admin.service.impl;

import com.pethelp.admin.dto.AdminImageResponse;
import com.pethelp.admin.dto.RejectPostRequest;
import com.pethelp.admin.dto.UserBanResponse;
import com.pethelp.admin.service.AdminService;
import com.pethelp.common.exception.BadRequestException;
import com.pethelp.common.exception.ConflictException;
import com.pethelp.common.exception.ForbiddenException;
import com.pethelp.common.exception.NotFoundException;
import com.pethelp.pet.dto.ExactLocationResponse;
import com.pethelp.pet.entity.PetImage;
import com.pethelp.pet.mapper.PetImageMapper;
import com.pethelp.pet.service.LocationAccessService;
import com.pethelp.post.dto.PostResponse;
import com.pethelp.post.entity.PetPost;
import com.pethelp.post.mapper.PostMapper;
import com.pethelp.security.CurrentUser;
import com.pethelp.user.entity.User;
import com.pethelp.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AdminServiceImpl implements AdminService {

    private final PostMapper postMapper;
    private final PetImageMapper petImageMapper;
    private final UserMapper userMapper;
    private final LocationAccessService locationAccessService;

    public AdminServiceImpl(PostMapper postMapper, PetImageMapper petImageMapper,
                            UserMapper userMapper, LocationAccessService locationAccessService) {
        this.postMapper = postMapper;
        this.petImageMapper = petImageMapper;
        this.userMapper = userMapper;
        this.locationAccessService = locationAccessService;
    }

    @Override
    public PostResponse takeDownPost(CurrentUser cu, Long postId) {
        requireOps(cu);
        PetPost post = postMapper.findById(postId);
        if (post == null) {
            throw new NotFoundException("帖子不存在");
        }
        if (postMapper.takeDown(postId) == 0) {
            throw new ConflictException("当前状态不可下架");
        }
        post.setStatus("CLOSED");
        return toPostResponse(post);
    }

    @Override
    public PostResponse rejectPost(CurrentUser cu, Long postId, RejectPostRequest request) {
        requireOps(cu);
        PetPost post = postMapper.findById(postId);
        if (post == null) {
            throw new NotFoundException("帖子不存在");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new BadRequestException("驳回原因不能为空");
        }
        if (postMapper.reject(postId, request.reason()) == 0) {
            throw new ConflictException("当前状态不可驳回");
        }
        post.setStatus("REJECTED");
        return toPostResponse(post);
    }

    @Override
    public PostResponse verifyPost(CurrentUser cu, Long postId) {
        requireOps(cu);
        PetPost post = postMapper.findById(postId);
        if (post == null) {
            throw new NotFoundException("帖子不存在");
        }
        if (postMapper.verify(postId) == 0) {
            throw new ConflictException("当前状态不可恢复");
        }
        post.setStatus("OPEN");
        return toPostResponse(post);
    }

    @Override
    public List<PostResponse> listPosts(CurrentUser cu, String status, int page, int size) {
        requireOps(cu);
        if (page < 1 || size < 1) {
            throw new BadRequestException("分页参数非法");
        }
        int offset = (page - 1) * size;
        return postMapper.listForAdmin(status, offset, size).stream()
                .map(this::toPostResponse)
                .toList();
    }

    @Override
    public AdminImageResponse approveImage(CurrentUser cu, Long imageId) {
        requireOps(cu);
        PetImage image = petImageMapper.findById(imageId);
        if (image == null) {
            throw new NotFoundException("图片不存在");
        }
        petImageMapper.updateReviewStatus(imageId, "APPROVED");
        image.setReviewStatus("APPROVED");
        return toImageResponse(image);
    }

    @Override
    public AdminImageResponse rejectImage(CurrentUser cu, Long imageId) {
        requireOps(cu);
        PetImage image = petImageMapper.findById(imageId);
        if (image == null) {
            throw new NotFoundException("图片不存在");
        }
        petImageMapper.updateReviewStatus(imageId, "REJECTED");
        image.setReviewStatus("REJECTED");
        return toImageResponse(image);
    }

    @Override
    public List<AdminImageResponse> listImages(CurrentUser cu, String reviewStatus, int page, int size) {
        requireOps(cu);
        if (page < 1 || size < 1) {
            throw new BadRequestException("分页参数非法");
        }
        int offset = (page - 1) * size;
        return petImageMapper.list(reviewStatus, offset, size).stream()
                .map(this::toImageResponse)
                .toList();
    }

    @Override
    public UserBanResponse banUser(CurrentUser cu, Long userId) {
        if (!cu.isAdmin()) {
            throw new ForbiddenException("仅管理员可封禁");
        }
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new NotFoundException("用户不存在");
        }
        if ("BANNED".equals(user.getStatus())) {
            throw new ConflictException("用户已被封禁");
        }
        userMapper.updateStatus(userId, "BANNED");
        return new UserBanResponse(userId, "BANNED");
    }

    @Override
    public ExactLocationResponse getPetExactLocation(CurrentUser cu, Long petId) {
        requireOps(cu);
        return locationAccessService.getExactLocationForOps(petId, cu.id());
    }

    private void requireOps(CurrentUser cu) {
        if (!cu.isOps()) {
            throw new ForbiddenException("仅运营人员可操作");
        }
    }

    private PostResponse toPostResponse(PetPost post) {
        return new PostResponse(post.getId(), post.getPetId(), post.getPublisherId(), post.getPostType(),
                post.getTitle(), post.getDescription(), post.getStatus(), post.getSpecies(),
                post.getGeoHash(), post.getCity(), post.getDistrict());
    }

    private AdminImageResponse toImageResponse(PetImage image) {
        return new AdminImageResponse(image.getId(), image.getPetId(), image.getUploaderId(),
                image.getFileType(), image.getReviewStatus());
    }
}
