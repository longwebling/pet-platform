package com.pethelp.pet;

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
import com.pethelp.pet.service.impl.LocationAccessServiceImpl;
import com.pethelp.user.entity.User;
import com.pethelp.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocationAccessServiceImplTest {

    private PetMapper petMapper;
    private LocationAccessGrantMapper grantMapper;
    private AddressVisibilityLogMapper visibilityLogMapper;
    private UserMapper userMapper;
    private LocationAccessService service;

    @BeforeEach
    void setUp() {
        petMapper = mock(PetMapper.class);
        grantMapper = mock(LocationAccessGrantMapper.class);
        visibilityLogMapper = mock(AddressVisibilityLogMapper.class);
        userMapper = mock(UserMapper.class);
        service = new LocationAccessServiceImpl(petMapper, grantMapper, visibilityLogMapper, userMapper);
    }

    @Test
    void grant_notOwner_throws403() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setPublisherId(99L);
        when(petMapper.findById(1L)).thenReturn(pet);

        assertThatThrownBy(() -> service.grant(1L, 5L, new GrantLocationAccessRequest(2L)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void grant_granteeNotFound_throws404() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setPublisherId(5L);
        when(petMapper.findById(1L)).thenReturn(pet);
        when(userMapper.findById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.grant(1L, 5L, new GrantLocationAccessRequest(999L)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void grant_granteeSelf_throws400() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setPublisherId(5L);
        when(petMapper.findById(1L)).thenReturn(pet);

        assertThatThrownBy(() -> service.grant(1L, 5L, new GrantLocationAccessRequest(5L)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void grant_duplicateActive_throws409() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setPublisherId(5L);
        when(petMapper.findById(1L)).thenReturn(pet);
        when(userMapper.findById(2L)).thenReturn(new User());
        when(grantMapper.findActiveByGranteeAndPet(2L, 1L)).thenReturn(new LocationAccessGrant());

        assertThatThrownBy(() -> service.grant(1L, 5L, new GrantLocationAccessRequest(2L)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void grant_success_returnsActive() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setPublisherId(5L);
        when(petMapper.findById(1L)).thenReturn(pet);
        when(userMapper.findById(2L)).thenReturn(new User());
        when(grantMapper.findActiveByGranteeAndPet(2L, 1L)).thenReturn(null);
        doAnswer(inv -> {
            LocationAccessGrant g = inv.getArgument(0);
            g.setId(10L);
            return 1;
        }).when(grantMapper).insert(any(LocationAccessGrant.class));

        LocationGrantResponse response = service.grant(1L, 5L, new GrantLocationAccessRequest(2L));

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.petId()).isEqualTo(1L);
        assertThat(response.granteeId()).isEqualTo(2L);
        assertThat(response.locationType()).isEqualTo("EXACT");
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    void getExactLocation_owner_noAuditWritten() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setPublisherId(5L);
        when(petMapper.findById(1L)).thenReturn(pet);
        when(petMapper.findExactLocation(1L)).thenReturn(location(116.397, 39.908));

        ExactLocationResponse response = service.getExactLocation(1L, 5L);

        assertThat(response.lng()).isEqualTo(116.397);
        assertThat(response.lat()).isEqualTo(39.908);
        verify(visibilityLogMapper, never()).insert(any());
    }

    @Test
    void getExactLocation_grantee_writesAudit() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setPublisherId(99L);
        when(petMapper.findById(1L)).thenReturn(pet);
        LocationAccessGrant grant = new LocationAccessGrant();
        grant.setId(10L);
        grant.setGranteeId(5L);
        when(grantMapper.findActiveByGranteeAndPet(5L, 1L)).thenReturn(grant);
        when(petMapper.findExactLocation(1L)).thenReturn(location(116.397, 39.908));

        ExactLocationResponse response = service.getExactLocation(1L, 5L);

        assertThat(response.lng()).isEqualTo(116.397);
        verify(visibilityLogMapper).insert(any(AddressVisibilityLog.class));
    }

    @Test
    void getExactLocation_unAuthorized_throws403() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setPublisherId(99L);
        when(petMapper.findById(1L)).thenReturn(pet);
        when(grantMapper.findActiveByGranteeAndPet(5L, 1L)).thenReturn(null);

        assertThatThrownBy(() -> service.getExactLocation(1L, 5L))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getExactLocation_noLocation_throws404() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setPublisherId(5L);
        when(petMapper.findById(1L)).thenReturn(pet);
        when(petMapper.findExactLocation(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.getExactLocation(1L, 5L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getExactLocation_grantee_noLocation_writesNoAudit() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setPublisherId(99L);
        when(petMapper.findById(1L)).thenReturn(pet);
        LocationAccessGrant grant = new LocationAccessGrant();
        grant.setGranteeId(5L);
        when(grantMapper.findActiveByGranteeAndPet(5L, 1L)).thenReturn(grant);
        when(petMapper.findExactLocation(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.getExactLocation(1L, 5L))
                .isInstanceOf(NotFoundException.class);
        verify(visibilityLogMapper, never()).insert(any());
    }

    @Test
    void revoke_notGrantor_throws403() {
        LocationAccessGrant grant = new LocationAccessGrant();
        grant.setId(10L);
        grant.setGrantorId(99L);
        grant.setStatus("ACTIVE");
        when(grantMapper.findById(10L)).thenReturn(grant);

        assertThatThrownBy(() -> service.revoke(10L, 5L))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void revoke_notActive_throws409() {
        LocationAccessGrant grant = new LocationAccessGrant();
        grant.setId(10L);
        grant.setGrantorId(5L);
        grant.setStatus("REVOKED");
        when(grantMapper.findById(10L)).thenReturn(grant);

        assertThatThrownBy(() -> service.revoke(10L, 5L))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void revoke_success_returnsRevoked() {
        LocationAccessGrant grant = new LocationAccessGrant();
        grant.setId(10L);
        grant.setGrantorId(5L);
        grant.setGranteeId(2L);
        grant.setPetId(1L);
        grant.setLocationType("EXACT");
        grant.setStatus("ACTIVE");
        when(grantMapper.findById(10L)).thenReturn(grant);
        when(grantMapper.revoke(10L, 5L)).thenReturn(1);

        LocationGrantResponse response = service.revoke(10L, 5L);

        assertThat(response.status()).isEqualTo("REVOKED");
    }

    @Test
    void getExactLocationForOps_petNotFound_404() {
        when(petMapper.findById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.getExactLocationForOps(99L, 2L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getExactLocationForOps_writesAuditAndReturnsCoords() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setPublisherId(10L);
        when(petMapper.findById(1L)).thenReturn(pet);
        when(petMapper.findExactLocation(1L)).thenReturn(location(121.5, 31.2));

        ExactLocationResponse response = service.getExactLocationForOps(1L, 2L);

        assertThat(response.lng()).isEqualTo(121.5);
        assertThat(response.lat()).isEqualTo(31.2);

        ArgumentCaptor<AddressVisibilityLog> captor = ArgumentCaptor.forClass(AddressVisibilityLog.class);
        verify(visibilityLogMapper).insert(captor.capture());
        AddressVisibilityLog log = captor.getValue();
        assertThat(log.getViewerId()).isEqualTo(2L);
        assertThat(log.getPetId()).isEqualTo(1L);
        assertThat(log.getAddressType()).isEqualTo("EXACT");
        assertThat(log.getPostId()).isNull();
    }

    private Map<String, Object> location(double lng, double lat) {
        Map<String, Object> map = new HashMap<>();
        map.put("lng", lng);
        map.put("lat", lat);
        return map;
    }
}
