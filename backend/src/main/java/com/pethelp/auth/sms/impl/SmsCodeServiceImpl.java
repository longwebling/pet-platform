package com.pethelp.auth.sms.impl;

import com.pethelp.auth.sms.SmsCodeService;
import com.pethelp.auth.sms.SmsSender;
import com.pethelp.common.exception.RateLimitException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

@Service
public class SmsCodeServiceImpl implements SmsCodeService {

    private static final int CODE_TTL_MINUTES = 5;
    private static final long DAILY_LIMIT = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SmsSender smsSender;
    private final StringRedisTemplate redis;

    public SmsCodeServiceImpl(SmsSender smsSender, StringRedisTemplate redis) {
        this.smsSender = smsSender;
        this.redis = redis;
    }

    @Override
    public void sendCode(String phone) {
        String minuteKey = "sms:rate:minute:" + phone;
        Boolean firstInMinute = redis.opsForValue().setIfAbsent(minuteKey, "1", Duration.ofSeconds(60));
        if (Boolean.FALSE.equals(firstInMinute)) {
            throw new RateLimitException("发送太频繁，请稍后再试");
        }

        String dayKey = "sms:rate:day:" + phone;
        Long dayCount = redis.opsForValue().increment(dayKey);
        if (dayCount != null && dayCount == 1L) {
            redis.expire(dayKey, Duration.ofHours(24));
        }
        if (dayCount != null && dayCount > DAILY_LIMIT) {
            throw new RateLimitException("今日发送次数已达上限");
        }

        String code = generateCode();
        redis.opsForValue().set("sms:code:" + phone, code, Duration.ofMinutes(CODE_TTL_MINUTES));
        smsSender.send(phone, code);
    }

    @Override
    public boolean verify(String phone, String code) {
        String key = "sms:code:" + phone;
        String stored = redis.opsForValue().get(key);
        if (stored != null && stored.equals(code)) {
            redis.delete(key);
            return true;
        }
        return false;
    }

    private String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
