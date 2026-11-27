package com.pethelp.report.dto;

public record CreateReportRequest(String targetType, Long targetId, String reason) {
}
