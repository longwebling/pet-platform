package com.pethelp.pet.mapper;

import com.pethelp.pet.entity.PetOwnershipHistory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PetOwnershipHistoryMapper {

    int insert(PetOwnershipHistory history);
}
