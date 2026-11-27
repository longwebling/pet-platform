package com.pethelp.auth;

import com.pethelp.auth.dto.LoginRequest;
import com.pethelp.auth.service.impl.AuthServiceImpl;
import com.pethelp.auth.sms.SmsCodeService;
import com.pethelp.auth.token.JwtService;
import com.pethelp.common.exception.ForbiddenException;
import com.pethelp.user.entity.User;
import com.pethelp.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceImplTest {

    private UserMapper userMapper;
    private SmsCodeService smsCodeService;
    private JwtService jwtService;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        smsCodeService = mock(SmsCodeService.class);
        jwtService = mock(JwtService.class);
        service = new AuthServiceImpl(userMapper, smsCodeService, jwtService);
    }

    @Test
    void login_bannedUser_throwsForbidden() {
        when(smsCodeService.verify("13800138000", "123456")).thenReturn(true);
        User banned = new User();
        banned.setId(1L);
        banned.setStatus("BANNED");
        when(userMapper.findByPhone("13800138000")).thenReturn(banned);

        assertThatThrownBy(() -> service.login(new LoginRequest("13800138000", "123456")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("账号已被封禁");
    }
}
