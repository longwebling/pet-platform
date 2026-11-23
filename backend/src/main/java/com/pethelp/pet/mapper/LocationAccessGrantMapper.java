package com.pethelp.pet.mapper;

import com.pethelp.pet.entity.LocationAccessGrant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LocationAccessGrantMapper {

    int insert(LocationAccessGrant grant);

    LocationAccessGrant findById(@Param("id") Long id);

    LocationAccessGrant findActiveByGranteeAndPet(@Param("granteeId") Long granteeId, @Param("petId") Long petId);

    int revoke(@Param("id") Long id, @Param("grantorId") Long grantorId);
}
