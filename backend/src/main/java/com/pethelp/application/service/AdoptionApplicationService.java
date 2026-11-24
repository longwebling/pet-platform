package com.pethelp.application.service;

import com.pethelp.application.dto.ApplicationResponse;
import com.pethelp.application.dto.ApproveResponse;
import com.pethelp.application.dto.SubmitApplicationRequest;

public interface AdoptionApplicationService {

    ApplicationResponse submit(Long postId, Long currentUserId, SubmitApplicationRequest request);

    ApplicationResponse withdraw(Long applicationId, Long currentUserId);

    ApplicationResponse reject(Long applicationId, Long currentUserId);

    ApproveResponse approve(Long applicationId, Long currentUserId);
}
