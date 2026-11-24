package com.pethelp.application.controller;

import com.pethelp.application.dto.ApplicationResponse;
import com.pethelp.application.dto.ApproveResponse;
import com.pethelp.application.dto.SubmitApplicationRequest;
import com.pethelp.application.service.AdoptionApplicationService;
import com.pethelp.common.ApiResponse;
import com.pethelp.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AdoptionApplicationController {

    private final AdoptionApplicationService adoptionApplicationService;

    public AdoptionApplicationController(AdoptionApplicationService adoptionApplicationService) {
        this.adoptionApplicationService = adoptionApplicationService;
    }

    @PostMapping("/adoption-posts/{id}/applications")
    public ApiResponse<ApplicationResponse> submit(@PathVariable Long id,
                                                   @AuthenticationPrincipal CurrentUser currentUser,
                                                   @RequestBody SubmitApplicationRequest request) {
        return ApiResponse.ok(adoptionApplicationService.submit(id, currentUser.id(), request));
    }

    @PostMapping("/adoption-applications/{id}/withdraw")
    public ApiResponse<ApplicationResponse> withdraw(@PathVariable Long id,
                                                     @AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(adoptionApplicationService.withdraw(id, currentUser.id()));
    }

    @PostMapping("/adoption-applications/{id}/approve")
    public ApiResponse<ApproveResponse> approve(@PathVariable Long id,
                                                @AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(adoptionApplicationService.approve(id, currentUser.id()));
    }

    @PostMapping("/adoption-applications/{id}/reject")
    public ApiResponse<ApplicationResponse> reject(@PathVariable Long id,
                                                   @AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(adoptionApplicationService.reject(id, currentUser.id()));
    }
}
