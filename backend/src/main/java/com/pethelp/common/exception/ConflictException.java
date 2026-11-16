package com.pethelp.common.exception;

import org.springframework.http.HttpStatus;

public class ConflictException extends BusinessException {

    public ConflictException(String message) {
        super(40900, message, HttpStatus.CONFLICT);
    }
}
