package com.pethelp.admin.controller;

import com.pethelp.admin.dto.AdminImageResponse;
import com.pethelp.admin.dto.RejectPostRequest;
import com.pethelp.admin.dto.UserBanResponse;
import com.pethelp.admin.service.AdminService;
import com.pethelp.common.ApiResponse;
import com.pethelp.pet.dto.ExactLocationResponse;
import com.pethelp.post.dto.PostResponse;
import com.pethelp.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping("/posts/{id}/take-down")
    public ApiResponse<PostResponse> takeDownPost(@PathVariable Long id,
                                                  @AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(adminService.takeDownPost(currentUser, id));
    }

    @PostMapping("/posts/{id}/reject")
    public ApiResponse<PostResponse> rejectPost(@PathVariable Long id,
                                                @AuthenticationPrincipal CurrentUser currentUser,
                                                @RequestBody RejectPostRequest request) {
        return ApiResponse.ok(adminService.rejectPost(currentUser, id, request));
    }

    @PostMapping("/posts/{id}/verify")
    public ApiResponse<PostResponse> verifyPost(@PathVariable Long id,
                                                @AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(adminService.verifyPost(currentUser, id));
    }

    @GetMapping("/posts")
    public ApiResponse<List<PostResponse>> listPosts(@AuthenticationPrincipal CurrentUser currentUser,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminService.listPosts(currentUser, status, page, size));
    }

    @PostMapping("/images/{id}/approve")
    public ApiResponse<AdminImageResponse> approveImage(@PathVariable Long id,
                                                        @AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(adminService.approveImage(currentUser, id));
    }

    @PostMapping("/images/{id}/reject")
    public ApiResponse<AdminImageResponse> rejectImage(@PathVariable Long id,
                                                       @AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(adminService.rejectImage(currentUser, id));
    }

    @GetMapping("/images")
    public ApiResponse<List<AdminImageResponse>> listImages(@AuthenticationPrincipal CurrentUser currentUser,
                                                            @RequestParam(required = false) String reviewStatus,
                                                            @RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminService.listImages(currentUser, reviewStatus, page, size));
    }

    @PostMapping("/users/{id}/ban")
    public ApiResponse<UserBanResponse> banUser(@PathVariable Long id,
                                                @AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(adminService.banUser(currentUser, id));
    }

    @PostMapping("/pets/{id}/exact-location")
    public ApiResponse<ExactLocationResponse> getPetExactLocation(@PathVariable Long id,
                                                                  @AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(adminService.getPetExactLocation(currentUser, id));
    }
}
