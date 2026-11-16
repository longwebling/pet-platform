package com.pethelp.pet.controller;

import com.pethelp.common.ApiResponse;
import com.pethelp.pet.dto.CreatePetRequest;
import com.pethelp.pet.dto.PetResponse;
import com.pethelp.pet.dto.UpdatePetRequest;
import com.pethelp.pet.service.PetService;
import com.pethelp.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pets")
public class PetController {

    private final PetService petService;

    public PetController(PetService petService) {
        this.petService = petService;
    }

    @PostMapping
    public ApiResponse<PetResponse> create(@AuthenticationPrincipal CurrentUser currentUser,
                                           @RequestBody CreatePetRequest request) {
        return ApiResponse.ok(petService.create(currentUser.id(), request));
    }

    @GetMapping("/{id}")
    public ApiResponse<PetResponse> getById(@PathVariable Long id) {
        return ApiResponse.ok(petService.getById(id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<PetResponse> update(@PathVariable Long id,
                                           @AuthenticationPrincipal CurrentUser currentUser,
                                           @RequestBody UpdatePetRequest request) {
        return ApiResponse.ok(petService.update(id, currentUser.id(), request));
    }
}
