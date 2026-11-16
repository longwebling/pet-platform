package com.pethelp.picture.review;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutoApproveImageReviewServiceTest {

    @Test
    void review_alwaysApproved() {
        AutoApproveImageReviewService service = new AutoApproveImageReviewService();

        assertThat(service.review(new byte[]{1}, "pets/1/a.jpg")).isEqualTo("APPROVED");
    }
}
