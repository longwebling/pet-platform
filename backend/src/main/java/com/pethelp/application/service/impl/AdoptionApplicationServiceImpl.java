package com.pethelp.application.service.impl;

import com.pethelp.application.dto.ApplicationResponse;
import com.pethelp.application.dto.ApproveResponse;
import com.pethelp.application.dto.SubmitApplicationRequest;
import com.pethelp.application.entity.AdoptionApplication;
import com.pethelp.application.mapper.AdoptionApplicationMapper;
import com.pethelp.application.service.AdoptionApplicationService;
import com.pethelp.arrangement.service.ArrangementService;
import com.pethelp.common.exception.ConflictException;
import com.pethelp.common.exception.ForbiddenException;
import com.pethelp.common.exception.NotFoundException;
import com.pethelp.pet.entity.Pet;
import com.pethelp.pet.mapper.PetMapper;
import com.pethelp.pet.service.DispositionAuthorizationService;
import com.pethelp.post.entity.PetPost;
import com.pethelp.post.mapper.PostMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdoptionApplicationServiceImpl implements AdoptionApplicationService {

    private static final String REJECT_OTHERS_REASON = "该宠物已被其他申请人匹配";

    private final PostMapper postMapper;
    private final AdoptionApplicationMapper adoptionApplicationMapper;
    private final DispositionAuthorizationService dispositionAuthorizationService;
    private final ArrangementService arrangementService;
    private final PetMapper petMapper;

    public AdoptionApplicationServiceImpl(PostMapper postMapper,
                                          AdoptionApplicationMapper adoptionApplicationMapper,
                                          DispositionAuthorizationService dispositionAuthorizationService,
                                          ArrangementService arrangementService,
                                          PetMapper petMapper) {
        this.postMapper = postMapper;
        this.adoptionApplicationMapper = adoptionApplicationMapper;
        this.dispositionAuthorizationService = dispositionAuthorizationService;
        this.arrangementService = arrangementService;
        this.petMapper = petMapper;
    }

    @Override
    public ApplicationResponse submit(Long postId, Long currentUserId, SubmitApplicationRequest request) {
        PetPost post = postMapper.findById(postId);
        if (post == null) {
            throw new NotFoundException("帖子不存在");
        }
        if (!"ADOPTION".equals(post.getPostType()) || !"OPEN".equals(post.getStatus())) {
            throw new ConflictException("该帖子当前不可申请");
        }

        AdoptionApplication application = new AdoptionApplication();
        application.setPostId(postId);
        application.setAdopterId(currentUserId);
        application.setMessage(request.message());
        application.setStatus("PENDING");
        try {
            adoptionApplicationMapper.insert(application);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("您已提交过该帖子的申请");
        }
        return toResponse(application, "PENDING");
    }

    @Override
    @Transactional
    public ApplicationResponse withdraw(Long applicationId, Long currentUserId) {
        AdoptionApplication application = adoptionApplicationMapper.findById(applicationId);
        if (application == null) {
            throw new NotFoundException("领养申请不存在");
        }
        if (!"PENDING".equals(application.getStatus())) {
            throw new ConflictException("该申请已处理");
        }
        if (!currentUserId.equals(application.getAdopterId())) {
            throw new ForbiddenException("仅申请人可撤回");
        }
        adoptionApplicationMapper.withdraw(applicationId, application.getAdopterId());
        return toResponse(application, "WITHDRAWN");
    }

    @Override
    @Transactional
    public ApplicationResponse reject(Long applicationId, Long currentUserId) {
        AdoptionApplication application = adoptionApplicationMapper.findById(applicationId);
        if (application == null) {
            throw new NotFoundException("领养申请不存在");
        }
        if (!"PENDING".equals(application.getStatus())) {
            throw new ConflictException("该申请已处理");
        }
        PetPost post = postMapper.findById(application.getPostId());
        if (post == null) {
            throw new NotFoundException("帖子不存在");
        }
        if (!currentUserId.equals(post.getPublisherId())) {
            throw new ForbiddenException("仅发布者可拒绝");
        }
        adoptionApplicationMapper.reject(applicationId);
        return toResponse(application, "REJECTED");
    }

    @Override
    @Transactional
    public ApproveResponse approve(Long applicationId, Long currentUserId) {
        AdoptionApplication application = adoptionApplicationMapper.findById(applicationId);
        if (application == null) {
            throw new NotFoundException("领养申请不存在");
        }
        if (!"PENDING".equals(application.getStatus())) {
            throw new ConflictException("该申请已处理");
        }

        Long postId = application.getPostId();
        PetPost post = postMapper.findByIdForUpdate(postId);
        if (post == null) {
            throw new NotFoundException("帖子不存在");
        }
        if (!currentUserId.equals(post.getPublisherId())) {
            throw new ForbiddenException("仅发布者可审批");
        }
        Long petId = post.getPetId();
        if (!dispositionAuthorizationService.canApproveAdoption(petId, currentUserId)) {
            throw new ForbiddenException("无权审批该领养");
        }
        if (!"OPEN".equals(post.getStatus())) {
            throw new ConflictException("帖子当前不可审批");
        }
        if (postMapper.reserve(postId, applicationId) != 1) {
            throw new ConflictException("帖子已被其他申请人匹配");
        }

        Pet pet = petMapper.findById(petId);
        if (pet == null) {
            throw new NotFoundException("宠物不存在");
        }
        Long custodianId = pet.getCurrentCustodianId();

        Long arrangementId = arrangementService.create(petId, postId, applicationId,
                application.getAdopterId(), custodianId);

        int rejectedOthers = adoptionApplicationMapper.rejectOthers(postId, applicationId, REJECT_OTHERS_REASON);
        adoptionApplicationMapper.approve(applicationId);

        return new ApproveResponse(arrangementId, "RESERVED", rejectedOthers);
    }

    private ApplicationResponse toResponse(AdoptionApplication application, String status) {
        return new ApplicationResponse(application.getId(), application.getPostId(), application.getAdopterId(),
                application.getMessage(), status, application.getRejectReason());
    }
}
