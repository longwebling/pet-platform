package com.pethelp.post.controller;

import com.pethelp.common.ApiResponse;
import com.pethelp.post.dto.CreatePostRequest;
import com.pethelp.post.dto.PostResponse;
import com.pethelp.post.dto.UpdatePostRequest;
import com.pethelp.post.service.PostService;
import com.pethelp.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/pet-posts")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    @PostMapping
    public ApiResponse<PostResponse> create(@AuthenticationPrincipal CurrentUser currentUser,
                                            @RequestBody CreatePostRequest request) {
        return ApiResponse.ok(postService.create(currentUser.id(), request));
    }

    @GetMapping
    public ApiResponse<List<PostResponse>> list(@RequestParam(required = false) String postType,
                                                @RequestParam(required = false) String species,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(postService.list(postType, species, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<PostResponse> getById(@PathVariable Long id) {
        return ApiResponse.ok(postService.getById(id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<PostResponse> update(@PathVariable Long id,
                                            @AuthenticationPrincipal CurrentUser currentUser,
                                            @RequestBody UpdatePostRequest request) {
        return ApiResponse.ok(postService.update(id, currentUser.id(), request));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<PostResponse> close(@PathVariable Long id,
                                           @AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(postService.close(id, currentUser.id()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<PostResponse> cancel(@PathVariable Long id,
                                            @AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(postService.cancel(id, currentUser.id()));
    }

    @PostMapping("/{id}/convert-to-adoption")
    public ApiResponse<PostResponse> convertToAdoption(@PathVariable Long id,
                                                       @AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(postService.convertToAdoption(id, currentUser.id()));
    }
}
