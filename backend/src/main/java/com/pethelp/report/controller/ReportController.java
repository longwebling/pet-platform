package com.pethelp.report.controller;

import com.pethelp.common.ApiResponse;
import com.pethelp.report.dto.CreateReportRequest;
import com.pethelp.report.dto.HandleReportRequest;
import com.pethelp.report.dto.ReportResponse;
import com.pethelp.report.service.ReportService;
import com.pethelp.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/reports")
    public ApiResponse<ReportResponse> create(@AuthenticationPrincipal CurrentUser currentUser,
                                              @RequestBody CreateReportRequest request) {
        return ApiResponse.ok(reportService.create(currentUser, request));
    }

    @GetMapping("/admin/reports")
    public ApiResponse<List<ReportResponse>> list(@RequestParam(required = false) String status,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(reportService.list(status, page, size));
    }

    @PostMapping("/admin/reports/{id}/handle")
    public ApiResponse<ReportResponse> handle(@PathVariable Long id,
                                              @AuthenticationPrincipal CurrentUser currentUser,
                                              @RequestBody HandleReportRequest request) {
        return ApiResponse.ok(reportService.handle(currentUser, id, request));
    }
}
