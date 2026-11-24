package com.pethelp.arrangement.mapper;

import com.pethelp.arrangement.entity.AdoptionArrangement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AdoptionArrangementMapper {

    int insert(AdoptionArrangement arrangement);

    AdoptionArrangement findById(@Param("id") Long id);

    AdoptionArrangement findByIdForUpdate(@Param("id") Long id);

    int complete(@Param("id") Long id);

    int cancel(@Param("id") Long id);

    int abort(@Param("id") Long id, @Param("reason") String reason);
}
