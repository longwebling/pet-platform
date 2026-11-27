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

    PetImage findById(@Param("id") Long id);

    List<PetImage> list(@Param("reviewStatus") String reviewStatus, @Param("offset") int offset, @Param("limit") int limit);

    int updateReviewStatus(@Param("id") Long id, @Param("status") String status);
}
