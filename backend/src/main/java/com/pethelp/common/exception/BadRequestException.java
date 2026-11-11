package com.pethelp.common.exception;

import org.springframework.http.HttpStatus;

public class BadRequestException extends BusinessException {

    public BadRequestException(String message) {
        super(40000, message, HttpStatus.BAD_REQUEST);
    }
}
