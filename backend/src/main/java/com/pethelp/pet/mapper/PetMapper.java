package com.pethelp.pet.mapper;

import com.pethelp.pet.entity.Pet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

@Mapper
public interface PetMapper {

    int insert(Pet pet);

    Pet findById(@Param("id") Long id);

    Pet findByIdActive(@Param("id") Long id);

    int update(Pet pet);

    int publish(@Param("id") Long id, @Param("publisherId") Long publisherId);

    int archive(@Param("id") Long id, @Param("publisherId") Long publisherId);

    int softDelete(@Param("id") Long id, @Param("publisherId") Long publisherId);

    Map<String, Object> findExactLocation(@Param("id") Long id);
}
