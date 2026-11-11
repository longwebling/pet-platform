package com.pethelp.common;

import com.pethelp.common.exception.BusinessException;
import com.pethelp.common.exception.InvalidSmsCodeException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleBusinessException_mapsCodeAndStatus() {
        BusinessException ex = new InvalidSmsCodeException("验证码错误");

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().code()).isEqualTo(40100);
        assertThat(response.getBody().message()).isEqualTo("验证码错误");
    }

    @Test
    void handleUnexpected_returns500() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpected(new RuntimeException("boom"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().code()).isEqualTo(50000);
    }
}
