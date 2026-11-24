package com.pethelp.pet.mapper;

import com.pethelp.pet.entity.PetAuthorization;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PetAuthorizationMapper {

    PetAuthorization findActiveByPetAndGranteeAndType(@Param("petId") Long petId,
                                                       @Param("granteeId") Long granteeId,
                                                       @Param("authType") String authType);
}
