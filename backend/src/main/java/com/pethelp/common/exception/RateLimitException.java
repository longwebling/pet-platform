package com.pethelp.common.exception;

import org.springframework.http.HttpStatus;

public class RateLimitException extends BusinessException {

    public RateLimitException(String message) {
        super(42900, message, HttpStatus.TOO_MANY_REQUESTS);
    }
}
