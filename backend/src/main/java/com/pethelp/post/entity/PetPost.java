package com.pethelp.post.entity;

import java.time.OffsetDateTime;

public class PetPost {

    private Long id;
    private Long petId;
    private Long publisherId;
    private String postType;
    private String title;
    private String description;
    private String careType;
    private OffsetDateTime careStartTime;
    private OffsetDateTime careEndTime;
    private String status;
    private Long reservedApplicationId;
    private Long convertedToPostId;
    private String rejectReason;
    private OffsetDateTime verifiedAt;
    private OffsetDateTime completedAt;
    private Integer version;
    private String species;
    private String geoHash;
    private String city;
    private String district;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPetId() {
        return petId;
    }

    public void setPetId(Long petId) {
        this.petId = petId;
    }

    public Long getPublisherId() {
        return publisherId;
    }

    public void setPublisherId(Long publisherId) {
        this.publisherId = publisherId;
    }

    public String getPostType() {
        return postType;
    }

    public void setPostType(String postType) {
        this.postType = postType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCareType() {
        return careType;
    }

    public void setCareType(String careType) {
        this.careType = careType;
    }

    public OffsetDateTime getCareStartTime() {
        return careStartTime;
    }

    public void setCareStartTime(OffsetDateTime careStartTime) {
        this.careStartTime = careStartTime;
    }

    public OffsetDateTime getCareEndTime() {
        return careEndTime;
    }

    public void setCareEndTime(OffsetDateTime careEndTime) {
        this.careEndTime = careEndTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getReservedApplicationId() {
        return reservedApplicationId;
    }

    public void setReservedApplicationId(Long reservedApplicationId) {
        this.reservedApplicationId = reservedApplicationId;
    }

    public Long getConvertedToPostId() {
        return convertedToPostId;
    }

    public void setConvertedToPostId(Long convertedToPostId) {
        this.convertedToPostId = convertedToPostId;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public OffsetDateTime getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(OffsetDateTime verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(OffsetDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getSpecies() {
        return species;
    }

    public void setSpecies(String species) {
        this.species = species;
    }

    public String getGeoHash() {
        return geoHash;
    }

    public void setGeoHash(String geoHash) {
        this.geoHash = geoHash;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getDistrict() {
        return district;
    }

    public void setDistrict(String district) {
        this.district = district;
    }
}
