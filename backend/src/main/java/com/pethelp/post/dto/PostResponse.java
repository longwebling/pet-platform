package com.pethelp.post.dto;

public record PostResponse(Long id, Long petId, Long publisherId, String postType, String title,
                           String description, String status, String species, String geoHash,
                           String city, String district) {
}
