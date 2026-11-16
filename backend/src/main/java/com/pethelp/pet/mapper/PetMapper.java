package com.pethelp.pet.mapper;

import com.pethelp.pet.entity.Pet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PetMapper {

    int insert(Pet pet);

    Pet findById(@Param("id") Long id);

    Pet findByIdActive(@Param("id") Long id);
}
