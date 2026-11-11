package com.pethelp.auth;

import com.pethelp.auth.sms.SmsCodeService;
import com.pethelp.auth.sms.SmsSender;
import com.pethelp.auth.sms.impl.SmsCodeServiceImpl;
import com.pethelp.common.exception.RateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmsCodeServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private SmsSender smsSender;
    private SmsCodeService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        smsSender = mock(SmsSender.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new SmsCodeServiceImpl(smsSender, redis);
    }

    @Test
    void sendCode_generatesSixDigitCode_sendsAndStoresWithFiveMinuteTtl() {
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(valueOps.increment(anyString())).thenReturn(1L);

        service.sendCode("13800138000");

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(smsSender).send(eq("13800138000"), codeCaptor.capture());
        assertThat(codeCaptor.getValue()).matches("\\d{6}");
        verify(valueOps).set(eq("sms:code:13800138000"), codeCaptor.capture(), eq(Duration.ofMinutes(5)));
    }

    @Test
    void sendCode_rejectsWhenWithinSixtySeconds() {
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> service.sendCode("13800138000"))
                .isInstanceOf(RateLimitException.class);

        verify(smsSender, never()).send(anyString(), anyString());
    }

    @Test
    void sendCode_rejectsWhenDailyLimitExceeded() {
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(valueOps.increment(anyString())).thenReturn(11L);

        assertThatThrownBy(() -> service.sendCode("13800138000"))
                .isInstanceOf(RateLimitException.class);

        verify(smsSender, never()).send(anyString(), anyString());
    }

    @Test
    void verify_matchesStoredCode_returnsTrueAndConsumes() {
        when(valueOps.get("sms:code:13800138000")).thenReturn("123456");

        boolean result = service.verify("13800138000", "123456");

        assertThat(result).isTrue();
        verify(redis).delete("sms:code:13800138000");
    }

    @Test
    void verify_wrongCode_returnsFalse() {
        when(valueOps.get("sms:code:13800138000")).thenReturn("123456");

        boolean result = service.verify("13800138000", "000000");

        assertThat(result).isFalse();
        verify(redis, never()).delete(anyString());
    }
}
