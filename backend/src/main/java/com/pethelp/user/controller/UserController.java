package com.pethelp.user.controller;

import com.pethelp.common.ApiResponse;
import com.pethelp.security.CurrentUser;
import com.pethelp.user.dto.UpdateProfileRequest;
import com.pethelp.user.dto.UserProfileResponse;
import com.pethelp.user.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> me(@AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(userService.getProfile(currentUser.id()));
    }

    @PatchMapping("/me")
    public ApiResponse<UserProfileResponse> updateMe(@AuthenticationPrincipal CurrentUser currentUser,
                                                     @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(userService.updateProfile(currentUser.id(), request));
    }

    @GetMapping("/{id}")
    public ApiResponse<UserProfileResponse> getById(@PathVariable Long id) {
        return ApiResponse.ok(userService.getProfile(id));
    }
}
