package com.pethelp.pet.service.impl;

import com.pethelp.common.exception.BadRequestException;
import com.pethelp.common.exception.NotFoundException;
import com.pethelp.pet.dto.CreatePetRequest;
import com.pethelp.pet.dto.PetResponse;
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
        validate(request);
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

    private void validate(CreatePetRequest r) {
        if (r.species() == null || r.species().isBlank()) {
            throw new BadRequestException("物种不能为空");
        }
        if (!SPECIES.contains(r.species())) {
            throw new BadRequestException("物种取值非法");
        }
        if (r.gender() != null && !GENDERS.contains(r.gender())) {
            throw new BadRequestException("性别取值非法");
        }
        if (r.breed() != null && r.breed().length() > 64) {
            throw new BadRequestException("品种过长");
        }
        if (r.age() != null && r.age().length() > 32) {
            throw new BadRequestException("年龄过长");
        }
        if (r.city() != null && r.city().length() > 64) {
            throw new BadRequestException("城市过长");
        }
        if (r.district() != null && r.district().length() > 64) {
            throw new BadRequestException("区县过长");
        }
        if ((r.lng() == null) != (r.lat() == null)) {
            throw new BadRequestException("经纬度需成对提供");
        }
        if (r.lng() != null) {
            if (r.lng() < -180 || r.lng() > 180) {
                throw new BadRequestException("经度越界");
            }
            if (r.lat() < -90 || r.lat() > 90) {
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
