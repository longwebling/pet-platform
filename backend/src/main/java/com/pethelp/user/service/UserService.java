package com.pethelp.user.service;

import com.pethelp.user.dto.UpdateProfileRequest;
import com.pethelp.user.dto.UserProfileResponse;

public interface UserService {

    UserProfileResponse getProfile(Long id);

    UserProfileResponse updateProfile(Long id, UpdateProfileRequest request);
}
