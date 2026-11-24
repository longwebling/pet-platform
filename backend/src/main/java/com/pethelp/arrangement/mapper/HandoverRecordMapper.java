package com.pethelp.arrangement.mapper;

import com.pethelp.arrangement.entity.HandoverRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface HandoverRecordMapper {

    int insert(HandoverRecord record);

    HandoverRecord findByBizAndEvent(@Param("bizType") String bizType,
                                     @Param("bizId") Long bizId,
                                     @Param("event") String event);

    int confirmByFrom(@Param("id") Long id);

    int confirmByTo(@Param("id") Long id);
}
