package com.pethelp.pet.service.impl;

import com.pethelp.common.exception.BadRequestException;
import com.pethelp.common.exception.ConflictException;
import com.pethelp.common.exception.ForbiddenException;
import com.pethelp.common.exception.NotFoundException;
import com.pethelp.pet.dto.ExactLocationResponse;
import com.pethelp.pet.dto.GrantLocationAccessRequest;
import com.pethelp.pet.dto.LocationGrantResponse;
import com.pethelp.pet.entity.AddressVisibilityLog;
import com.pethelp.pet.entity.LocationAccessGrant;
import com.pethelp.pet.entity.Pet;
import com.pethelp.pet.mapper.AddressVisibilityLogMapper;
import com.pethelp.pet.mapper.LocationAccessGrantMapper;
import com.pethelp.pet.mapper.PetMapper;
import com.pethelp.pet.service.LocationAccessService;
import com.pethelp.user.mapper.UserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class LocationAccessServiceImpl implements LocationAccessService {

    private final PetMapper petMapper;
    private final LocationAccessGrantMapper grantMapper;
    private final AddressVisibilityLogMapper visibilityLogMapper;
    private final UserMapper userMapper;

    public LocationAccessServiceImpl(PetMapper petMapper, LocationAccessGrantMapper grantMapper,
                                     AddressVisibilityLogMapper visibilityLogMapper, UserMapper userMapper) {
        this.petMapper = petMapper;
        this.grantMapper = grantMapper;
        this.visibilityLogMapper = visibilityLogMapper;
        this.userMapper = userMapper;
    }

    @Override
    @Transactional
    public LocationGrantResponse grant(Long petId, Long currentUserId, GrantLocationAccessRequest request) {
        Pet pet = petMapper.findById(petId);
        if (pet == null) {
            throw new NotFoundException("宠物不存在");
        }
        if (!pet.getPublisherId().equals(currentUserId)) {
            throw new ForbiddenException("仅档案发布者可授权");
        }
        Long granteeId = request.granteeId();
        if (granteeId == null) {
            throw new BadRequestException("被授权用户不能为空");
        }
        if (granteeId.equals(currentUserId)) {
            throw new BadRequestException("不能授权给自己");
        }
        if (userMapper.findById(granteeId) == null) {
            throw new NotFoundException("用户不存在");
        }
        if (grantMapper.findActiveByGranteeAndPet(granteeId, petId) != null) {
            throw new ConflictException("已授权该用户");
        }

        LocationAccessGrant grant = new LocationAccessGrant();
        grant.setGrantorId(currentUserId);
        grant.setGranteeId(granteeId);
        grant.setPetId(petId);
        grant.setLocationType("EXACT");
        grant.setStatus("ACTIVE");
        try {
            grantMapper.insert(grant);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("已授权该用户");
        }
        return new LocationGrantResponse(grant.getId(), grant.getPetId(), grant.getGranteeId(),
                grant.getLocationType(), grant.getStatus());
    }

    @Override
    public ExactLocationResponse getExactLocation(Long petId, Long currentUserId) {
        Pet pet = petMapper.findById(petId);
        if (pet == null) {
            throw new NotFoundException("宠物不存在");
        }
        if (!pet.getPublisherId().equals(currentUserId)) {
            if (grantMapper.findActiveByGranteeAndPet(currentUserId, petId) == null) {
                throw new ForbiddenException("无权查看精确位置");
            }
            AddressVisibilityLog log = new AddressVisibilityLog();
            log.setViewerId(currentUserId);
            log.setPetId(petId);
            log.setPostId(null);
            log.setAddressType("EXACT");
            visibilityLogMapper.insert(log);
        }
        return resolveExactLocation(petId);
    }

    @Override
    public LocationGrantResponse revoke(Long grantId, Long currentUserId) {
        LocationAccessGrant grant = grantMapper.findById(grantId);
        if (grant == null) {
            throw new NotFoundException("授权不存在");
        }
        if (!grant.getGrantorId().equals(currentUserId)) {
            throw new ForbiddenException("仅授权者可撤销");
        }
        if (!"ACTIVE".equals(grant.getStatus())) {
            throw new ConflictException("授权已撤销");
        }
        if (grantMapper.revoke(grantId, currentUserId) == 0) {
            throw new ConflictException("授权已撤销");
        }
        return new LocationGrantResponse(grant.getId(), grant.getPetId(), grant.getGranteeId(),
                grant.getLocationType(), "REVOKED");
    }

    private ExactLocationResponse resolveExactLocation(Long petId) {
        Map<String, Object> location = petMapper.findExactLocation(petId);
        if (location == null || location.isEmpty()) {
            throw new NotFoundException("该宠物未设置位置");
        }
        double lng = ((Number) location.get("lng")).doubleValue();
        double lat = ((Number) location.get("lat")).doubleValue();
        return new ExactLocationResponse(lng, lat);
    }
}
