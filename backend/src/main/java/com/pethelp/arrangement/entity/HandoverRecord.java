package com.pethelp.arrangement.entity;

import java.time.OffsetDateTime;

public class HandoverRecord {

    private Long id;
    private String bizType;
    private Long bizId;
    private Long fromUserId;
    private Long toUserId;
    private String event;
    private Boolean confirmedByFrom;
    private Boolean confirmedByTo;
    private OffsetDateTime occurredAt;
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public Long getBizId() {
        return bizId;
    }

    public void setBizId(Long bizId) {
        this.bizId = bizId;
    }

    public Long getFromUserId() {
        return fromUserId;
    }

    public void setFromUserId(Long fromUserId) {
        this.fromUserId = fromUserId;
    }

    public Long getToUserId() {
        return toUserId;
    }

    public void setToUserId(Long toUserId) {
        this.toUserId = toUserId;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public Boolean getConfirmedByFrom() {
        return confirmedByFrom;
    }

    public void setConfirmedByFrom(Boolean confirmedByFrom) {
        this.confirmedByFrom = confirmedByFrom;
    }

    public Boolean getConfirmedByTo() {
        return confirmedByTo;
    }

    public void setConfirmedByTo(Boolean confirmedByTo) {
        this.confirmedByTo = confirmedByTo;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(OffsetDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
