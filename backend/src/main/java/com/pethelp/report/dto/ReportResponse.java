package com.pethelp.report.dto;

import java.time.OffsetDateTime;

public record ReportResponse(Long id, Long reporterId, String targetType, Long targetId,
                             String reason, String status, Long handledBy, OffsetDateTime createdAt) {
}
