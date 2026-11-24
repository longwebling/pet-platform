package com.pethelp.arrangement;

import com.pethelp.arrangement.dto.ArrangementResponse;
import com.pethelp.arrangement.dto.HandoverConfirmResponse;
import com.pethelp.arrangement.entity.AdoptionArrangement;
import com.pethelp.arrangement.entity.HandoverRecord;
import com.pethelp.arrangement.mapper.AdoptionArrangementMapper;
import com.pethelp.arrangement.mapper.HandoverRecordMapper;
import com.pethelp.arrangement.service.ArrangementService;
import com.pethelp.arrangement.service.impl.ArrangementServiceImpl;
import com.pethelp.common.exception.ForbiddenException;
import com.pethelp.pet.service.PetService;
import com.pethelp.post.mapper.PostMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArrangementServiceImplTest {

    private AdoptionArrangementMapper adoptionArrangementMapper;
    private HandoverRecordMapper handoverRecordMapper;
    private PostMapper postMapper;
    private PetService petService;
    private ArrangementService service;

    @BeforeEach
    void setUp() {
        adoptionArrangementMapper = mock(AdoptionArrangementMapper.class);
        handoverRecordMapper = mock(HandoverRecordMapper.class);
        postMapper = mock(PostMapper.class);
        petService = mock(PetService.class);
        service = new ArrangementServiceImpl(adoptionArrangementMapper, handoverRecordMapper, postMapper, petService);
    }

    private AdoptionArrangement pendingArrangement() {
        AdoptionArrangement a = new AdoptionArrangement();
        a.setId(1L);
        a.setPetId(1L);
        a.setPostId(2L);
        a.setApplicationId(3L);
        a.setAdopterId(7L);
        a.setCustodianId(8L);
        a.setStatus("PENDING_HANDOVER");
        return a;
    }

    @Test
    void create_insertsArrangementAndPrecreatesHandover() {
        doAnswer(inv -> {
            AdoptionArrangement a = inv.getArgument(0);
            a.setId(10L);
            return 1;
        }).when(adoptionArrangementMapper).insert(any(AdoptionArrangement.class));

        Long id = service.create(1L, 2L, 3L, 7L, 8L);

        assertThat(id).isEqualTo(10L);

        ArgumentCaptor<AdoptionArrangement> ac = ArgumentCaptor.forClass(AdoptionArrangement.class);
        verify(adoptionArrangementMapper).insert(ac.capture());
        AdoptionArrangement arrangement = ac.getValue();
        assertThat(arrangement.getStatus()).isEqualTo("PENDING_HANDOVER");
        assertThat(arrangement.getPetId()).isEqualTo(1L);
        assertThat(arrangement.getPostId()).isEqualTo(2L);
        assertThat(arrangement.getApplicationId()).isEqualTo(3L);
        assertThat(arrangement.getAdopterId()).isEqualTo(7L);
        assertThat(arrangement.getCustodianId()).isEqualTo(8L);

        ArgumentCaptor<HandoverRecord> hc = ArgumentCaptor.forClass(HandoverRecord.class);
        verify(handoverRecordMapper).insert(hc.capture());
        HandoverRecord record = hc.getValue();
        assertThat(record.getEvent()).isEqualTo("HANDOVER");
        assertThat(record.getBizType()).isEqualTo("ADOPTION");
        assertThat(record.getBizId()).isEqualTo(10L);
        assertThat(record.getFromUserId()).isEqualTo(8L);
        assertThat(record.getToUserId()).isEqualTo(7L);
        assertThat(record.getConfirmedByFrom()).isFalse();
        assertThat(record.getConfirmedByTo()).isFalse();
    }

    @Test
    void confirmHandover_notParticipant_throws403() {
        when(adoptionArrangementMapper.findByIdForUpdate(1L)).thenReturn(pendingArrangement());

        assertThatThrownBy(() -> service.confirmHandover(1L, 9L))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void confirmHandover_fromSideFirst_setsFromFlag() {
        when(adoptionArrangementMapper.findByIdForUpdate(1L)).thenReturn(pendingArrangement());
        HandoverRecord record = new HandoverRecord();
        record.setId(100L);
        record.setConfirmedByFrom(false);
        record.setConfirmedByTo(false);
        when(handoverRecordMapper.findByBizAndEvent("ADOPTION", 1L, "HANDOVER")).thenReturn(record);
        when(handoverRecordMapper.confirmByFrom(100L)).thenAnswer(inv -> {
            record.setConfirmedByFrom(true);
            return 1;
        });

        HandoverConfirmResponse response = service.confirmHandover(1L, 8L);

        verify(handoverRecordMapper).confirmByFrom(100L);
        verify(handoverRecordMapper, never()).confirmByTo(anyLong());
        assertThat(response.status()).isEqualTo("PENDING_HANDOVER");
        assertThat(response.fromConfirmed()).isTrue();
        assertThat(response.toConfirmed()).isFalse();
    }

    @Test
    void confirmHandover_bothConfirmed_completesAndTransfers() {
        when(adoptionArrangementMapper.findByIdForUpdate(1L)).thenReturn(pendingArrangement());
        HandoverRecord record = new HandoverRecord();
        record.setId(100L);
        record.setConfirmedByFrom(true);
        record.setConfirmedByTo(false);
        when(handoverRecordMapper.findByBizAndEvent("ADOPTION", 1L, "HANDOVER")).thenReturn(record);
        when(handoverRecordMapper.confirmByTo(100L)).thenAnswer(inv -> {
            record.setConfirmedByTo(true);
            return 1;
        });

        HandoverConfirmResponse response = service.confirmHandover(1L, 7L);

        verify(adoptionArrangementMapper).complete(1L);
        verify(postMapper).markCompleted(2L);
        verify(petService).transferOwnershipToAdopter(1L, 7L, 1L, 7L);
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.fromConfirmed()).isTrue();
        assertThat(response.toConfirmed()).isTrue();
    }

    @Test
    void confirmHandover_alreadyConfirmed_noop() {
        when(adoptionArrangementMapper.findByIdForUpdate(1L)).thenReturn(pendingArrangement());
        HandoverRecord record = new HandoverRecord();
        record.setId(100L);
        record.setConfirmedByFrom(true);
        record.setConfirmedByTo(false);
        when(handoverRecordMapper.findByBizAndEvent("ADOPTION", 1L, "HANDOVER")).thenReturn(record);
        when(handoverRecordMapper.confirmByFrom(100L)).thenReturn(0);

        HandoverConfirmResponse response = service.confirmHandover(1L, 8L);

        verify(adoptionArrangementMapper, never()).complete(anyLong());
        verify(postMapper, never()).markCompleted(anyLong());
        verify(petService, never()).transferOwnershipToAdopter(anyLong(), anyLong(), anyLong(), anyLong());
        assertThat(response.status()).isEqualTo("PENDING_HANDOVER");
        assertThat(response.fromConfirmed()).isTrue();
        assertThat(response.toConfirmed()).isFalse();
    }

    @Test
    void cancel_reservedArrangement_reopensPost() {
        when(adoptionArrangementMapper.findById(1L)).thenReturn(pendingArrangement());

        ArrangementResponse response = service.cancel(1L, 8L);

        verify(adoptionArrangementMapper).cancel(1L);
        verify(postMapper).reopen(2L);
        assertThat(response.status()).isEqualTo("CANCELLED");
    }

    @Test
    void abort_reservedArrangement_reopensPost() {
        when(adoptionArrangementMapper.findById(1L)).thenReturn(pendingArrangement());
        when(handoverRecordMapper.findByBizAndEvent("ADOPTION", 1L, "ABORT")).thenReturn(null);

        ArrangementResponse response = service.abort(1L, 8L, "领养人放弃");

        verify(adoptionArrangementMapper).abort(1L, "领养人放弃");
        verify(postMapper).reopen(2L);
        verify(handoverRecordMapper).insert(any(HandoverRecord.class));
        assertThat(response.status()).isEqualTo("ABORTED");
    }

    @Test
    void cancel_notParticipant_throws403() {
        when(adoptionArrangementMapper.findById(1L)).thenReturn(pendingArrangement());

        assertThatThrownBy(() -> service.cancel(1L, 9L))
                .isInstanceOf(ForbiddenException.class);

        verify(adoptionArrangementMapper, never()).cancel(anyLong());
        verify(postMapper, never()).reopen(anyLong());
    }

    @Test
    void abort_notParticipant_throws403() {
        when(adoptionArrangementMapper.findById(1L)).thenReturn(pendingArrangement());

        assertThatThrownBy(() -> service.abort(1L, 9L, "领养人放弃"))
                .isInstanceOf(ForbiddenException.class);

        verify(adoptionArrangementMapper, never()).abort(anyLong(), any());
        verify(postMapper, never()).reopen(anyLong());
        verify(handoverRecordMapper, never()).insert(any(HandoverRecord.class));
    }
}
