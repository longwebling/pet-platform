package com.pethelp.admin.service;

import com.pethelp.admin.dto.AdminImageResponse;
import com.pethelp.admin.dto.RejectPostRequest;
import com.pethelp.admin.dto.UserBanResponse;
import com.pethelp.pet.dto.ExactLocationResponse;
import com.pethelp.post.dto.PostResponse;
import com.pethelp.security.CurrentUser;

import java.util.List;

public interface AdminService {

    PostResponse takeDownPost(CurrentUser cu, Long postId);

    PostResponse rejectPost(CurrentUser cu, Long postId, RejectPostRequest request);

    PostResponse verifyPost(CurrentUser cu, Long postId);

    List<PostResponse> listPosts(CurrentUser cu, String status, int page, int size);

    AdminImageResponse approveImage(CurrentUser cu, Long imageId);

    AdminImageResponse rejectImage(CurrentUser cu, Long imageId);

    List<AdminImageResponse> listImages(CurrentUser cu, String reviewStatus, int page, int size);

    UserBanResponse banUser(CurrentUser cu, Long userId);

    ExactLocationResponse getPetExactLocation(CurrentUser cu, Long petId);
}
