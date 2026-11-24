package com.pethelp.application.mapper;

import com.pethelp.application.entity.AdoptionApplication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AdoptionApplicationMapper {

    int insert(AdoptionApplication application);

    AdoptionApplication findById(@Param("id") Long id);

    int withdraw(@Param("id") Long id, @Param("adopterId") Long adopterId);

    int reject(@Param("id") Long id);

    int approve(@Param("id") Long id);

    int rejectOthers(@Param("postId") Long postId, @Param("approvedId") Long approvedId, @Param("reason") String reason);
}
