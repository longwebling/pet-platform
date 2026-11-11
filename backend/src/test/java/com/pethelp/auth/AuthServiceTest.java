package com.pethelp.auth;

import com.pethelp.auth.dto.LoginRequest;
import com.pethelp.auth.dto.LoginResponse;
import com.pethelp.auth.service.AuthService;
import com.pethelp.auth.service.impl.AuthServiceImpl;
import com.pethelp.auth.sms.SmsCodeService;
import com.pethelp.auth.token.JwtService;
import com.pethelp.common.exception.InvalidSmsCodeException;
import com.pethelp.user.entity.User;
import com.pethelp.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private UserMapper userMapper;
    private SmsCodeService smsCodeService;
    private JwtService jwtService;
    private AuthService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        smsCodeService = mock(SmsCodeService.class);
        jwtService = mock(JwtService.class);
        service = new AuthServiceImpl(userMapper, smsCodeService, jwtService);
    }

    @Test
    void login_verifiesCodeAndCreatesNewUser_returnsToken() {
        when(smsCodeService.verify("13800138000", "123456")).thenReturn(true);
        when(userMapper.findByPhone("13800138000")).thenReturn(null);
        when(jwtService.generateToken(anyLong(), anyList())).thenReturn("token-abc");
        doAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            return 1;
        }).when(userMapper).insert(any(User.class));

        LoginResponse response = service.login(new LoginRequest("13800138000", "123456"));

        assertThat(response.token()).isEqualTo("token-abc");
        assertThat(response.userId()).isEqualTo(1L);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(userCaptor.capture());
        assertThat(userCaptor.getValue().getPhone()).isEqualTo("13800138000");
        assertThat(userCaptor.getValue().getRoles()).containsExactly("user");
        verify(jwtService).generateToken(eq(1L), eq(List.of("user")));
    }

    @Test
    void login_wrongCode_throws() {
        when(smsCodeService.verify("13800138000", "000000")).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("13800138000", "000000")))
                .isInstanceOf(InvalidSmsCodeException.class);

        verify(userMapper, never()).findByPhone(anyString());
    }

    @Test
    void login_existingUser_returnsTokenWithExistingRoles() {
        when(smsCodeService.verify("13800138000", "123456")).thenReturn(true);
        User existing = new User();
        existing.setId(7L);
        existing.setPhone("13800138000");
        existing.setRoles(List.of("user", "ops"));
        when(userMapper.findByPhone("13800138000")).thenReturn(existing);
        when(jwtService.generateToken(anyLong(), anyList())).thenReturn("token-xyz");

        LoginResponse response = service.login(new LoginRequest("13800138000", "123456"));

        assertThat(response.token()).isEqualTo("token-xyz");
        assertThat(response.userId()).isEqualTo(7L);
        verify(userMapper, never()).insert(any(User.class));
        verify(jwtService).generateToken(eq(7L), eq(List.of("user", "ops")));
    }
}
