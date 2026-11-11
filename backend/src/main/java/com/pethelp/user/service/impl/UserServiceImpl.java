package com.pethelp.user.service.impl;

import com.pethelp.common.exception.NotFoundException;
import com.pethelp.user.dto.UpdateProfileRequest;
import com.pethelp.user.dto.UserProfileResponse;
import com.pethelp.user.entity.User;
import com.pethelp.user.mapper.UserMapper;
import com.pethelp.user.service.UserService;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserProfileResponse getProfile(Long id) {
        User user = userMapper.findById(id);
        if (user == null) {
            throw new NotFoundException("用户不存在");
        }
        return toResponse(user);
    }

    @Override
    public UserProfileResponse updateProfile(Long id, UpdateProfileRequest request) {
        User user = userMapper.findById(id);
        if (user == null) {
            throw new NotFoundException("用户不存在");
        }
        user.setNickname(request.nickname());
        user.setAvatar(request.avatar());
        user.setBio(request.bio());
        userMapper.updateProfile(user);
        return getProfile(id);
    }

    private UserProfileResponse toResponse(User user) {
        return new UserProfileResponse(
                user.getId(), user.getPhone(), user.getNickname(), user.getAvatar(), user.getBio());
    }
}
