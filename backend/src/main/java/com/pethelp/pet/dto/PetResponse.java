package com.pethelp.pet.dto;

public record PetResponse(Long id, String species, String breed, String age, String gender,
                          Boolean sterilized, Boolean vaccinated, String healthDesc, String status,
                          String sourceType, String ownershipStatus, String authorizationStatus,
                          String geoHash, String city, String district) {
}
