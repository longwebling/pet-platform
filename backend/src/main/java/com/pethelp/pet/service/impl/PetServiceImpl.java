package com.pethelp.pet.service.impl;

import com.pethelp.common.exception.BadRequestException;
import com.pethelp.common.exception.ConflictException;
import com.pethelp.common.exception.ForbiddenException;
import com.pethelp.common.exception.NotFoundException;
import com.pethelp.pet.dto.CreatePetRequest;
import com.pethelp.pet.dto.PetResponse;
import com.pethelp.pet.dto.UpdatePetRequest;
import com.pethelp.pet.entity.Pet;
import com.pethelp.pet.mapper.PetMapper;
import com.pethelp.pet.service.PetService;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class PetServiceImpl implements PetService {

    private static final Set<String> SPECIES = Set.of("CAT", "DOG", "OTHER");
    private static final Set<String> GENDERS = Set.of("MALE", "FEMALE", "UNKNOWN");

    private final PetMapper petMapper;

    public PetServiceImpl(PetMapper petMapper) {
        this.petMapper = petMapper;
    }

    @Override
    public PetResponse create(Long currentUserId, CreatePetRequest request) {
        validate(request.species(), request.breed(), request.age(), request.gender(),
                request.city(), request.district(), request.lng(), request.lat());
        Pet pet = new Pet();
        pet.setPublisherId(currentUserId);
        pet.setOwnerId(currentUserId);
        pet.setCurrentCustodianId(currentUserId);
        pet.setSourceType("OWNED");
        pet.setOwnershipStatus("OWNED");
        pet.setAuthorizationStatus("SELF");
        pet.setStatus("DRAFT");
        pet.setSpecies(request.species());
        pet.setBreed(request.breed());
        pet.setAge(request.age());
        pet.setGender(request.gender());
        pet.setSterilized(request.sterilized());
        pet.setVaccinated(request.vaccinated());
        pet.setHealthDesc(request.healthDesc());
        pet.setLng(request.lng());
        pet.setLat(request.lat());
        pet.setCity(request.city());
        pet.setDistrict(request.district());
        petMapper.insert(pet);
        return toResponse(petMapper.findById(pet.getId()));
    }

    @Override
    public PetResponse getById(Long petId) {
        Pet pet = petMapper.findByIdActive(petId);
        if (pet == null) {
            throw new NotFoundException("宠物不存在");
        }
        return toResponse(pet);
    }

    @Override
    public PetResponse update(Long petId, Long currentUserId, UpdatePetRequest request) {
        validate(request.species(), request.breed(), request.age(), request.gender(),
                request.city(), request.district(), request.lng(), request.lat());
        Pet pet = petMapper.findById(petId);
        if (pet == null) {
            throw new NotFoundException("宠物不存在");
        }
        if (!pet.getPublisherId().equals(currentUserId)) {
            throw new ForbiddenException("仅档案发布者可操作");
        }
        if (!("DRAFT".equals(pet.getStatus()) || "ACTIVE".equals(pet.getStatus()))) {
            throw new ConflictException("当前状态不可编辑");
        }
        pet.setSpecies(request.species());
        pet.setBreed(request.breed());
        pet.setAge(request.age());
        pet.setGender(request.gender());
        pet.setSterilized(request.sterilized());
        pet.setVaccinated(request.vaccinated());
        pet.setHealthDesc(request.healthDesc());
        pet.setLng(request.lng());
        pet.setLat(request.lat());
        pet.setCity(request.city());
        pet.setDistrict(request.district());
        if (petMapper.update(pet) == 0) {
            throw new ConflictException("当前状态不可编辑");
        }
        return toResponse(petMapper.findById(petId));
    }

    @Override
    public PetResponse publish(Long petId, Long currentUserId) {
        Pet pet = petMapper.findById(petId);
        if (pet == null) {
            throw new NotFoundException("宠物不存在");
        }
        if (!pet.getPublisherId().equals(currentUserId)) {
            throw new ForbiddenException("仅档案发布者可操作");
        }
        if (!"DRAFT".equals(pet.getStatus())) {
            throw new ConflictException("当前状态不可发布");
        }
        if (petMapper.publish(petId, currentUserId) == 0) {
            throw new ConflictException("当前状态不可发布");
        }
        return toResponse(petMapper.findById(petId));
    }

    @Override
    public PetResponse archive(Long petId, Long currentUserId) {
        Pet pet = petMapper.findById(petId);
        if (pet == null) {
            throw new NotFoundException("宠物不存在");
        }
        if (!pet.getPublisherId().equals(currentUserId)) {
            throw new ForbiddenException("仅档案发布者可操作");
        }
        if (!"ACTIVE".equals(pet.getStatus())) {
            throw new ConflictException("当前状态不可归档");
        }
        if (petMapper.archive(petId, currentUserId) == 0) {
            throw new ConflictException("当前状态不可归档");
        }
        return toResponse(petMapper.findById(petId));
    }

    @Override
    public void delete(Long petId, Long currentUserId) {
        Pet pet = petMapper.findById(petId);
        if (pet == null) {
            throw new NotFoundException("宠物不存在");
        }
        if (!pet.getPublisherId().equals(currentUserId)) {
            throw new ForbiddenException("仅档案发布者可操作");
        }
        if (!"ACTIVE".equals(pet.getStatus())) {
            throw new ConflictException("当前状态不可删除");
        }
        if (petMapper.softDelete(petId, currentUserId) == 0) {
            throw new ConflictException("当前状态不可删除");
        }
    }

    private void validate(String species, String breed, String age, String gender,
                          String city, String district, Double lng, Double lat) {
        if (species == null || species.isBlank()) {
            throw new BadRequestException("物种不能为空");
        }
        if (!SPECIES.contains(species)) {
            throw new BadRequestException("物种取值非法");
        }
        if (gender != null && !GENDERS.contains(gender)) {
            throw new BadRequestException("性别取值非法");
        }
        if (breed != null && breed.length() > 64) {
            throw new BadRequestException("品种过长");
        }
        if (age != null && age.length() > 32) {
            throw new BadRequestException("年龄过长");
        }
        if (city != null && city.length() > 64) {
            throw new BadRequestException("城市过长");
        }
        if (district != null && district.length() > 64) {
            throw new BadRequestException("区县过长");
        }
        if ((lng == null) != (lat == null)) {
            throw new BadRequestException("经纬度需成对提供");
        }
        if (lng != null) {
            if (lng < -180 || lng > 180) {
                throw new BadRequestException("经度越界");
            }
            if (lat < -90 || lat > 90) {
                throw new BadRequestException("纬度越界");
            }
        }
    }

    private PetResponse toResponse(Pet pet) {
        return new PetResponse(
                pet.getId(), pet.getSpecies(), pet.getBreed(), pet.getAge(), pet.getGender(),
                pet.getSterilized(), pet.getVaccinated(), pet.getHealthDesc(), pet.getStatus(),
                pet.getSourceType(), pet.getOwnershipStatus(), pet.getAuthorizationStatus(),
                pet.getGeoHash(), pet.getCity(), pet.getDistrict());
    }
}
