package com.pethelp.report.mapper;

import com.pethelp.report.entity.Report;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ReportMapper {

    int insert(Report report);

    Report findById(@Param("id") Long id);

    List<Report> list(@Param("status") String status, @Param("offset") int offset, @Param("limit") int limit);

    int handle(@Param("id") Long id, @Param("status") String status, @Param("handledBy") Long handledBy);
}
