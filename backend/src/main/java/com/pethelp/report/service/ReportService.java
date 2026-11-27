package com.pethelp.report.service;

import com.pethelp.report.dto.CreateReportRequest;
import com.pethelp.report.dto.HandleReportRequest;
import com.pethelp.report.dto.ReportResponse;
import com.pethelp.security.CurrentUser;

import java.util.List;

public interface ReportService {

    ReportResponse create(CurrentUser currentUser, CreateReportRequest request);

    List<ReportResponse> list(String status, int page, int size);

    ReportResponse handle(CurrentUser currentUser, Long reportId, HandleReportRequest request);
}
