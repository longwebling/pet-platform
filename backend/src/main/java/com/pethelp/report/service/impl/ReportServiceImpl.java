package com.pethelp.report.service.impl;

import com.pethelp.common.exception.BadRequestException;
import com.pethelp.common.exception.ConflictException;
import com.pethelp.common.exception.ForbiddenException;
import com.pethelp.common.exception.NotFoundException;
import com.pethelp.pet.mapper.PetMapper;
import com.pethelp.post.mapper.PostMapper;
import com.pethelp.report.dto.CreateReportRequest;
import com.pethelp.report.dto.HandleReportRequest;
import com.pethelp.report.dto.ReportResponse;
import com.pethelp.report.entity.Report;
import com.pethelp.report.mapper.ReportMapper;
import com.pethelp.report.service.ReportService;
import com.pethelp.security.CurrentUser;
import com.pethelp.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

@Service
public class ReportServiceImpl implements ReportService {

    private static final Set<String> TARGET_TYPES = Set.of("PET", "POST", "USER");
    private static final Set<String> ACTIONS = Set.of("RESOLVED", "DISMISSED");

    private final ReportMapper reportMapper;
    private final PetMapper petMapper;
    private final PostMapper postMapper;
    private final UserMapper userMapper;

    public ReportServiceImpl(ReportMapper reportMapper, PetMapper petMapper, PostMapper postMapper, UserMapper userMapper) {
        this.reportMapper = reportMapper;
        this.petMapper = petMapper;
        this.postMapper = postMapper;
        this.userMapper = userMapper;
    }

    @Override
    public ReportResponse create(CurrentUser currentUser, CreateReportRequest request) {
        String targetType = request.targetType();
        if (targetType == null || !TARGET_TYPES.contains(targetType)) {
            throw new BadRequestException("举报目标类型非法");
        }
        boolean exists = switch (targetType) {
            case "PET" -> petMapper.findById(request.targetId()) != null;
            case "POST" -> postMapper.findById(request.targetId()) != null;
            case "USER" -> userMapper.findById(request.targetId()) != null;
            default -> false;
        };
        if (!exists) {
            throw new NotFoundException("举报目标不存在");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new BadRequestException("举报原因不能为空");
        }

        Report report = new Report();
        report.setReporterId(currentUser.id());
        report.setTargetType(targetType);
        report.setTargetId(request.targetId());
        report.setReason(request.reason());
        report.setStatus("PENDING");
        report.setCreatedAt(OffsetDateTime.now());
        reportMapper.insert(report);
        return toResponse(report);
    }

    @Override
    public List<ReportResponse> list(String status, int page, int size) {
        if (page < 1 || size < 1) {
            throw new BadRequestException("分页参数非法");
        }
        int offset = (page - 1) * size;
        return reportMapper.list(status, offset, size).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public ReportResponse handle(CurrentUser currentUser, Long reportId, HandleReportRequest request) {
        if (!currentUser.isOps()) {
            throw new ForbiddenException("仅运营人员可处理举报");
        }
        Report report = reportMapper.findById(reportId);
        if (report == null) {
            throw new NotFoundException("举报不存在");
        }
        if (!"PENDING".equals(report.getStatus())) {
            throw new ConflictException("举报已处理");
        }
        String action = request.action();
        if (action == null || !ACTIONS.contains(action)) {
            throw new BadRequestException("处理动作非法");
        }
        reportMapper.handle(reportId, action, currentUser.id());
        report.setStatus(action);
        report.setHandledBy(currentUser.id());
        return toResponse(report);
    }

    private ReportResponse toResponse(Report report) {
        return new ReportResponse(report.getId(), report.getReporterId(), report.getTargetType(),
                report.getTargetId(), report.getReason(), report.getStatus(),
                report.getHandledBy(), report.getCreatedAt());
    }
}
