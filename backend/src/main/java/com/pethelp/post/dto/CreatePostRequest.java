package com.pethelp.post.dto;

public record CreatePostRequest(Long petId, String postType, String title, String description) {
}
