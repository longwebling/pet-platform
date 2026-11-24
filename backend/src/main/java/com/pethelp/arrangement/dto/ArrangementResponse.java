package com.pethelp.arrangement.dto;

public record ArrangementResponse(Long id, Long petId, Long postId, Long applicationId,
                                  Long adopterId, Long custodianId, String status) {
}
