package com.pethelp.post.service;

import com.pethelp.post.dto.CreatePostRequest;
import com.pethelp.post.dto.PostResponse;
import com.pethelp.post.dto.UpdatePostRequest;

import java.util.List;

public interface PostService {

    PostResponse create(Long currentUserId, CreatePostRequest request);

    PostResponse getById(Long postId);

    List<PostResponse> list(String postType, String species, int page, int size);

    PostResponse update(Long postId, Long currentUserId, UpdatePostRequest request);

    PostResponse close(Long postId, Long currentUserId);

    PostResponse cancel(Long postId, Long currentUserId);

    PostResponse convertToAdoption(Long postId, Long currentUserId);
}
