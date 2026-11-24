package com.pethelp.arrangement.service.impl;

import com.pethelp.arrangement.dto.ArrangementResponse;
import com.pethelp.arrangement.dto.HandoverConfirmResponse;
import com.pethelp.arrangement.entity.AdoptionArrangement;
import com.pethelp.arrangement.entity.HandoverRecord;
import com.pethelp.arrangement.mapper.AdoptionArrangementMapper;
import com.pethelp.arrangement.mapper.HandoverRecordMapper;
import com.pethelp.arrangement.service.ArrangementService;
import com.pethelp.common.exception.ConflictException;
import com.pethelp.common.exception.ForbiddenException;
import com.pethelp.common.exception.NotFoundException;
import com.pethelp.pet.service.PetService;
import com.pethelp.post.mapper.PostMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArrangementServiceImpl implements ArrangementService {

    private static final String BIZ_TYPE = "ADOPTION";
    private static final String EVENT_HANDOVER = "HANDOVER";
    private static final String EVENT_ABORT = "ABORT";

    private final AdoptionArrangementMapper adoptionArrangementMapper;
    private final HandoverRecordMapper handoverRecordMapper;
    private final PostMapper postMapper;
    private final PetService petService;

    public ArrangementServiceImpl(AdoptionArrangementMapper adoptionArrangementMapper,
                                  HandoverRecordMapper handoverRecordMapper,
                                  PostMapper postMapper,
                                  PetService petService) {
        this.adoptionArrangementMapper = adoptionArrangementMapper;
        this.handoverRecordMapper = handoverRecordMapper;
        this.postMapper = postMapper;
        this.petService = petService;
    }

    @Override
    @Transactional
    public Long create(Long petId, Long postId, Long applicationId, Long adopterId, Long custodianId) {
        AdoptionArrangement arrangement = new AdoptionArrangement();
        arrangement.setPetId(petId);
        arrangement.setPostId(postId);
        arrangement.setApplicationId(applicationId);
        arrangement.setAdopterId(adopterId);
        arrangement.setCustodianId(custodianId);
        arrangement.setStatus("PENDING_HANDOVER");
        adoptionArrangementMapper.insert(arrangement);

        HandoverRecord handover = new HandoverRecord();
        handover.setBizType(BIZ_TYPE);
        handover.setBizId(arrangement.getId());
        handover.setEvent(EVENT_HANDOVER);
        handover.setFromUserId(custodianId);
        handover.setToUserId(adopterId);
        handover.setConfirmedByFrom(false);
        handover.setConfirmedByTo(false);
        handoverRecordMapper.insert(handover);

        return arrangement.getId();
    }

    @Override
    @Transactional
    public HandoverConfirmResponse confirmHandover(Long arrangementId, Long currentUserId) {
        AdoptionArrangement arrangement = adoptionArrangementMapper.findByIdForUpdate(arrangementId);
        if (arrangement == null) {
            throw new NotFoundException("领养安排不存在");
        }
        if (!"PENDING_HANDOVER".equals(arrangement.getStatus())) {
            throw new ConflictException("当前状态不可确认交接");
        }

        boolean isFrom;
        if (currentUserId.equals(arrangement.getCustodianId())) {
            isFrom = true;
        } else if (currentUserId.equals(arrangement.getAdopterId())) {
            isFrom = false;
        } else {
            throw new ForbiddenException("仅交接双方可确认");
        }

        HandoverRecord record = handoverRecordMapper.findByBizAndEvent(BIZ_TYPE, arrangementId, EVENT_HANDOVER);
        if (isFrom) {
            handoverRecordMapper.confirmByFrom(record.getId());
        } else {
            handoverRecordMapper.confirmByTo(record.getId());
        }

        record = handoverRecordMapper.findByBizAndEvent(BIZ_TYPE, arrangementId, EVENT_HANDOVER);
        boolean fromConfirmed = Boolean.TRUE.equals(record.getConfirmedByFrom());
        boolean toConfirmed = Boolean.TRUE.equals(record.getConfirmedByTo());

        String status = arrangement.getStatus();
        if (fromConfirmed && toConfirmed) {
            adoptionArrangementMapper.complete(arrangementId);
            postMapper.markCompleted(arrangement.getPostId());
            petService.transferOwnershipToAdopter(arrangement.getPetId(), arrangement.getAdopterId(),
                    arrangementId, currentUserId);
            status = "COMPLETED";
        }

        return new HandoverConfirmResponse(status, fromConfirmed, toConfirmed);
    }

    @Override
    @Transactional
    public ArrangementResponse cancel(Long arrangementId, Long currentUserId) {
        AdoptionArrangement arrangement = adoptionArrangementMapper.findById(arrangementId);
        if (arrangement == null) {
            throw new NotFoundException("领养安排不存在");
        }
        if (!"PENDING_HANDOVER".equals(arrangement.getStatus())) {
            throw new ConflictException("当前状态不可取消");
        }
        if (!currentUserId.equals(arrangement.getAdopterId())
                && !currentUserId.equals(arrangement.getCustodianId())) {
            throw new ForbiddenException("仅交接双方可取消");
        }
        adoptionArrangementMapper.cancel(arrangementId);
        postMapper.reopen(arrangement.getPostId());
        return toResponse(arrangement, "CANCELLED");
    }

    @Override
    @Transactional
    public ArrangementResponse abort(Long arrangementId, Long currentUserId, String reason) {
        AdoptionArrangement arrangement = adoptionArrangementMapper.findById(arrangementId);
        if (arrangement == null) {
            throw new NotFoundException("领养安排不存在");
        }
        if (!"PENDING_HANDOVER".equals(arrangement.getStatus())) {
            throw new ConflictException("当前状态不可中止");
        }
        if (!currentUserId.equals(arrangement.getAdopterId())
                && !currentUserId.equals(arrangement.getCustodianId())) {
            throw new ForbiddenException("仅交接双方可中止");
        }
        adoptionArrangementMapper.abort(arrangementId, reason);
        postMapper.reopen(arrangement.getPostId());

        if (handoverRecordMapper.findByBizAndEvent(BIZ_TYPE, arrangementId, EVENT_ABORT) == null) {
            HandoverRecord record = new HandoverRecord();
            record.setBizType(BIZ_TYPE);
            record.setBizId(arrangementId);
            record.setEvent(EVENT_ABORT);
            record.setFromUserId(arrangement.getCustodianId());
            record.setToUserId(arrangement.getAdopterId());
            record.setConfirmedByFrom(false);
            record.setConfirmedByTo(false);
            try {
                handoverRecordMapper.insert(record);
            } catch (DuplicateKeyException e) {
                // 并发中止时唯一约束命中，视为已存在，忽略
            }
        }

        return toResponse(arrangement, "ABORTED");
    }

    private ArrangementResponse toResponse(AdoptionArrangement arrangement, String status) {
        return new ArrangementResponse(arrangement.getId(), arrangement.getPetId(), arrangement.getPostId(),
                arrangement.getApplicationId(), arrangement.getAdopterId(), arrangement.getCustodianId(), status);
    }
}
