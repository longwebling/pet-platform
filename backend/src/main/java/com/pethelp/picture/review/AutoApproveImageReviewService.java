package com.pethelp.picture.review;

public class AutoApproveImageReviewService implements ImageReviewService {

    @Override
    public String review(byte[] content, String fileKey) {
        return "APPROVED";
    }
}
