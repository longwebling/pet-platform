package com.pethelp.arrangement.service;

import com.pethelp.arrangement.dto.ArrangementResponse;
import com.pethelp.arrangement.dto.HandoverConfirmResponse;

public interface ArrangementService {

    Long create(Long petId, Long postId, Long applicationId, Long adopterId, Long custodianId);

    HandoverConfirmResponse confirmHandover(Long arrangementId, Long currentUserId);

    ArrangementResponse cancel(Long arrangementId, Long currentUserId);

    ArrangementResponse abort(Long arrangementId, Long currentUserId, String reason);
}
