package com.pethelp.common.exception;

import org.springframework.http.HttpStatus;

public class ForbiddenException extends BusinessException {

    public ForbiddenException(String message) {
        super(40300, message, HttpStatus.FORBIDDEN);
    }
}
