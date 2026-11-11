package com.pethelp.common.exception;

import org.springframework.http.HttpStatus;

public class InvalidSmsCodeException extends BusinessException {

    public InvalidSmsCodeException(String message) {
        super(40100, message, HttpStatus.UNAUTHORIZED);
    }
}
