package com.pethelp.pet.mapper;

import com.pethelp.pet.entity.AddressVisibilityLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AddressVisibilityLogMapper {

    int insert(AddressVisibilityLog log);
}
