package com.pethelp.pet.controller;

import com.pethelp.common.ApiResponse;
import com.pethelp.common.exception.BadRequestException;
import com.pethelp.pet.dto.PetImageResponse;
import com.pethelp.pet.service.PetImageService;
import com.pethelp.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/pets")
public class PetImageController {

    private final PetImageService petImageService;

    public PetImageController(PetImageService petImageService) {
        this.petImageService = petImageService;
    }

    @PostMapping("/{id}/images")
    public ApiResponse<PetImageResponse> upload(@PathVariable Long id,
                                                @AuthenticationPrincipal CurrentUser currentUser,
                                                @RequestParam("file") MultipartFile file) {
        try {
            return ApiResponse.ok(petImageService.upload(id, currentUser.id(), file.getBytes()));
        } catch (IOException e) {
            throw new BadRequestException("文件读取失败");
        }
    }

    @GetMapping("/{id}/images")
    public ApiResponse<List<PetImageResponse>> list(@PathVariable Long id) {
        return ApiResponse.ok(petImageService.list(id));
    }
}
