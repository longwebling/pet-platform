package com.pethelp.pet.controller;

import com.pethelp.common.ApiResponse;
import com.pethelp.pet.dto.ExactLocationResponse;
import com.pethelp.pet.dto.GrantLocationAccessRequest;
import com.pethelp.pet.dto.LocationGrantResponse;
import com.pethelp.pet.service.LocationAccessService;
import com.pethelp.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class LocationAccessController {

    private final LocationAccessService locationAccessService;

    public LocationAccessController(LocationAccessService locationAccessService) {
        this.locationAccessService = locationAccessService;
    }

    @PostMapping("/pets/{id}/location-grants")
    public ApiResponse<LocationGrantResponse> grant(@PathVariable Long id,
                                                    @AuthenticationPrincipal CurrentUser currentUser,
                                                    @RequestBody GrantLocationAccessRequest request) {
        return ApiResponse.ok(locationAccessService.grant(id, currentUser.id(), request));
    }

    @GetMapping("/pets/{id}/exact-location")
    public ApiResponse<ExactLocationResponse> getExactLocation(@PathVariable Long id,
                                                               @AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(locationAccessService.getExactLocation(id, currentUser.id()));
    }

    @PostMapping("/location-grants/{id}/revoke")
    public ApiResponse<LocationGrantResponse> revoke(@PathVariable Long id,
                                                     @AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(locationAccessService.revoke(id, currentUser.id()));
    }
}
