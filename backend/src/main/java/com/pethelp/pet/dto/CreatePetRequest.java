package com.pethelp.pet.dto;

public record CreatePetRequest(String species, String breed, String age, String gender,
                               Boolean sterilized, Boolean vaccinated, String healthDesc,
                               Double lng, Double lat, String city, String district) {
}
