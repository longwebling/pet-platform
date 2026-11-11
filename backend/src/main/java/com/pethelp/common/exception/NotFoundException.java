package com.pethelp.common.exception;

import org.springframework.http.HttpStatus;

public class NotFoundException extends BusinessException {

    public NotFoundException(String message) {
        super(40400, message, HttpStatus.NOT_FOUND);
    }
}
