package com.pethelp.pet.mapper;

import com.pethelp.pet.entity.PetImage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PetImageMapper {

    int insert(PetImage image);

    int countByPetIdAndType(@Param("petId") Long petId, @Param("fileType") String fileType);

    List<PetImage> findByPetIdPublicApproved(@Param("petId") Long petId);
}
