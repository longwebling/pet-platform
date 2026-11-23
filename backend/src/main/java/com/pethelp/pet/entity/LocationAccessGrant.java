package com.pethelp.pet.entity;

public class LocationAccessGrant {

    private Long id;
    private Long grantorId;
    private Long granteeId;
    private Long petId;
    private String locationType;
    private String status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getGrantorId() {
        return grantorId;
    }

    public void setGrantorId(Long grantorId) {
        this.grantorId = grantorId;
    }

    public Long getGranteeId() {
        return granteeId;
    }

    public void setGranteeId(Long granteeId) {
        this.granteeId = granteeId;
    }

    public Long getPetId() {
        return petId;
    }

    public void setPetId(Long petId) {
        this.petId = petId;
    }

    public String getLocationType() {
        return locationType;
    }

    public void setLocationType(String locationType) {
        this.locationType = locationType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
