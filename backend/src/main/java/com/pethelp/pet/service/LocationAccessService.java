package com.pethelp.pet.service;

import com.pethelp.pet.dto.ExactLocationResponse;
import com.pethelp.pet.dto.GrantLocationAccessRequest;
import com.pethelp.pet.dto.LocationGrantResponse;

public interface LocationAccessService {

    LocationGrantResponse grant(Long petId, Long currentUserId, GrantLocationAccessRequest request);

    ExactLocationResponse getExactLocation(Long petId, Long currentUserId);

    ExactLocationResponse getExactLocationForOps(Long petId, Long viewerId);

    LocationGrantResponse revoke(Long grantId, Long currentUserId);
}
