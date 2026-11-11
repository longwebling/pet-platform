package com.pethelp.user.dto;

public record UserProfileResponse(Long id, String phone, String nickname, String avatar, String bio) {
}
