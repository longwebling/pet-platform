package com.pethelp.report;

import com.pethelp.common.exception.BadRequestException;
import com.pethelp.common.exception.ConflictException;
import com.pethelp.common.exception.ForbiddenException;
import com.pethelp.common.exception.NotFoundException;
import com.pethelp.pet.entity.Pet;
import com.pethelp.pet.mapper.PetMapper;
import com.pethelp.post.mapper.PostMapper;
import com.pethelp.report.dto.CreateReportRequest;
import com.pethelp.report.dto.HandleReportRequest;
import com.pethelp.report.dto.ReportResponse;
import com.pethelp.report.entity.Report;
import com.pethelp.report.mapper.ReportMapper;
import com.pethelp.report.service.impl.ReportServiceImpl;
import com.pethelp.security.CurrentUser;
import com.pethelp.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportServiceImplTest {

    private ReportMapper reportMapper;
    private PetMapper petMapper;
    private PostMapper postMapper;
    private UserMapper userMapper;
    private ReportServiceImpl service;

    @BeforeEach
    void setUp() {
        reportMapper = mock(ReportMapper.class);
        petMapper = mock(PetMapper.class);
        postMapper = mock(PostMapper.class);
        userMapper = mock(UserMapper.class);
        service = new ReportServiceImpl(reportMapper, petMapper, postMapper, userMapper);
    }

    @Test
    void create_petTarget_ok() {
        Pet pet = new Pet();
        pet.setId(10L);
        when(petMapper.findById(10L)).thenReturn(pet);
        when(reportMapper.insert(any(Report.class))).thenAnswer(inv -> {
            Report r = inv.getArgument(0);
            r.setId(100L);
            return 1;
        });

        CurrentUser reporter = new CurrentUser(1L, List.of("user"));
        ReportResponse resp = service.create(reporter, new CreateReportRequest("PET", 10L, "测试举报"));

        assertThat(resp.id()).isEqualTo(100L);
        assertThat(resp.reporterId()).isEqualTo(1L);
        assertThat(resp.targetType()).isEqualTo("PET");
        assertThat(resp.targetId()).isEqualTo(10L);
        assertThat(resp.reason()).isEqualTo("测试举报");
        assertThat(resp.status()).isEqualTo("PENDING");
        assertThat(resp.handledBy()).isNull();
        assertThat(resp.createdAt()).isNotNull();
    }

    @Test
    void create_badTargetType_400() {
        CurrentUser reporter = new CurrentUser(1L, List.of("user"));
        assertThatThrownBy(() -> service.create(reporter, new CreateReportRequest("INVALID", 1L, "reason")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void create_targetNotFound_404() {
        when(petMapper.findById(99L)).thenReturn(null);
        CurrentUser reporter = new CurrentUser(1L, List.of("user"));
        assertThatThrownBy(() -> service.create(reporter, new CreateReportRequest("PET", 99L, "reason")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_blankReason_400() {
        Pet pet = new Pet();
        pet.setId(10L);
        when(petMapper.findById(10L)).thenReturn(pet);
        CurrentUser reporter = new CurrentUser(1L, List.of("user"));
        assertThatThrownBy(() -> service.create(reporter, new CreateReportRequest("PET", 10L, "   ")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void handle_byOps_pending_ok() {
        Report report = new Report();
        report.setId(1L);
        report.setReporterId(1L);
        report.setTargetType("PET");
        report.setTargetId(10L);
        report.setReason("reason");
        report.setStatus("PENDING");
        report.setCreatedAt(OffsetDateTime.now());
        when(reportMapper.findById(1L)).thenReturn(report);
        when(reportMapper.handle(1L, "RESOLVED", 2L)).thenReturn(1);

        CurrentUser ops = new CurrentUser(2L, List.of("ops"));
        ReportResponse resp = service.handle(ops, 1L, new HandleReportRequest("RESOLVED"));

        assertThat(resp.status()).isEqualTo("RESOLVED");
        assertThat(resp.handledBy()).isEqualTo(2L);
    }

    @Test
    void handle_byPlainUser_403() {
        CurrentUser plain = new CurrentUser(3L, List.of("user"));
        assertThatThrownBy(() -> service.handle(plain, 1L, new HandleReportRequest("RESOLVED")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void handle_notPending_409() {
        Report report = new Report();
        report.setId(1L);
        report.setStatus("RESOLVED");
        when(reportMapper.findById(1L)).thenReturn(report);
        CurrentUser ops = new CurrentUser(2L, List.of("ops"));
        assertThatThrownBy(() -> service.handle(ops, 1L, new HandleReportRequest("RESOLVED")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void handle_badAction_400() {
        Report report = new Report();
        report.setId(1L);
        report.setStatus("PENDING");
        when(reportMapper.findById(1L)).thenReturn(report);
        CurrentUser ops = new CurrentUser(2L, List.of("ops"));
        assertThatThrownBy(() -> service.handle(ops, 1L, new HandleReportRequest("BOGUS")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void list_validPagination_ok() {
        Report report = new Report();
        report.setId(1L);
        report.setReporterId(1L);
        report.setTargetType("PET");
        report.setTargetId(10L);
        report.setReason("reason");
        report.setStatus("PENDING");
        report.setCreatedAt(OffsetDateTime.now());
        when(reportMapper.list("PENDING", 0, 20)).thenReturn(List.of(report));

        List<ReportResponse> list = service.list("PENDING", 1, 20);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).status()).isEqualTo("PENDING");
    }
}
