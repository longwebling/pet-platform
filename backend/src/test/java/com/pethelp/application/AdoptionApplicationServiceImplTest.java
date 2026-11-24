package com.pethelp.application;

import com.pethelp.application.dto.ApplicationResponse;
import com.pethelp.application.dto.ApproveResponse;
import com.pethelp.application.dto.SubmitApplicationRequest;
import com.pethelp.application.entity.AdoptionApplication;
import com.pethelp.application.mapper.AdoptionApplicationMapper;
import com.pethelp.application.service.AdoptionApplicationService;
import com.pethelp.application.service.impl.AdoptionApplicationServiceImpl;
import com.pethelp.arrangement.service.ArrangementService;
import com.pethelp.common.exception.ConflictException;
import com.pethelp.common.exception.ForbiddenException;
import com.pethelp.pet.entity.Pet;
import com.pethelp.pet.mapper.PetMapper;
import com.pethelp.pet.service.DispositionAuthorizationService;
import com.pethelp.post.entity.PetPost;
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

class AdoptionApplicationServiceImplTest {

    private PostMapper postMapper;
    private AdoptionApplicationMapper adoptionApplicationMapper;
    private DispositionAuthorizationService dispositionAuthorizationService;
    private ArrangementService arrangementService;
    private PetMapper petMapper;
    private AdoptionApplicationService service;

    @BeforeEach
    void setUp() {
        postMapper = mock(PostMapper.class);
        adoptionApplicationMapper = mock(AdoptionApplicationMapper.class);
        dispositionAuthorizationService = mock(DispositionAuthorizationService.class);
        arrangementService = mock(ArrangementService.class);
        petMapper = mock(PetMapper.class);
        service = new AdoptionApplicationServiceImpl(postMapper, adoptionApplicationMapper,
                dispositionAuthorizationService, arrangementService, petMapper);
    }

    private PetPost adoptionPost() {
        PetPost post = new PetPost();
        post.setId(10L);
        post.setPetId(1L);
        post.setPublisherId(5L);
        post.setPostType("ADOPTION");
        post.setStatus("OPEN");
        return post;
    }

    private AdoptionApplication pendingApplication() {
        AdoptionApplication application = new AdoptionApplication();
        application.setId(30L);
        application.setPostId(10L);
        application.setAdopterId(7L);
        application.setStatus("PENDING");
        return application;
    }

    @Test
    void submit_postNotOpen_throws409() {
        PetPost post = adoptionPost();
        post.setStatus("RESERVED");
        when(postMapper.findById(10L)).thenReturn(post);

        assertThatThrownBy(() -> service.submit(10L, 7L, new SubmitApplicationRequest("我想领养")))
                .isInstanceOf(ConflictException.class);

        verify(adoptionApplicationMapper, never()).insert(any(AdoptionApplication.class));
    }

    @Test
    void submit_postNotAdoption_throws409() {
        PetPost post = adoptionPost();
        post.setPostType("FINDING");
        when(postMapper.findById(10L)).thenReturn(post);

        assertThatThrownBy(() -> service.submit(10L, 7L, new SubmitApplicationRequest("我想领养")))
                .isInstanceOf(ConflictException.class);

        verify(adoptionApplicationMapper, never()).insert(any(AdoptionApplication.class));
    }

    @Test
    void submit_success_insertsPending() {
        when(postMapper.findById(10L)).thenReturn(adoptionPost());
        doAnswer(inv -> {
            AdoptionApplication a = inv.getArgument(0);
            a.setId(30L);
            return 1;
        }).when(adoptionApplicationMapper).insert(any(AdoptionApplication.class));

        ApplicationResponse response = service.submit(10L, 7L, new SubmitApplicationRequest("我想领养"));

        assertThat(response.id()).isEqualTo(30L);
        assertThat(response.postId()).isEqualTo(10L);
        assertThat(response.adopterId()).isEqualTo(7L);
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.rejectReason()).isNull();

        ArgumentCaptor<AdoptionApplication> captor = ArgumentCaptor.forClass(AdoptionApplication.class);
        verify(adoptionApplicationMapper).insert(captor.capture());
        AdoptionApplication application = captor.getValue();
        assertThat(application.getStatus()).isEqualTo("PENDING");
        assertThat(application.getPostId()).isEqualTo(10L);
        assertThat(application.getAdopterId()).isEqualTo(7L);
        assertThat(application.getMessage()).isEqualTo("我想领养");
    }

    @Test
    void withdraw_notOwner_throws403() {
        when(adoptionApplicationMapper.findById(30L)).thenReturn(pendingApplication());

        assertThatThrownBy(() -> service.withdraw(30L, 99L))
                .isInstanceOf(ForbiddenException.class);

        verify(adoptionApplicationMapper, never()).withdraw(anyLong(), anyLong());
    }

    @Test
    void reject_publisherOnly_throws403() {
        when(adoptionApplicationMapper.findById(30L)).thenReturn(pendingApplication());
        when(postMapper.findById(10L)).thenReturn(adoptionPost());

        assertThatThrownBy(() -> service.reject(30L, 99L))
                .isInstanceOf(ForbiddenException.class);

        verify(adoptionApplicationMapper, never()).reject(anyLong());
    }

    @Test
    void approve_notPublisher_throws403() {
        when(adoptionApplicationMapper.findById(30L)).thenReturn(pendingApplication());
        when(postMapper.findByIdForUpdate(10L)).thenReturn(adoptionPost());

        assertThatThrownBy(() -> service.approve(30L, 99L))
                .isInstanceOf(ForbiddenException.class);

        verify(dispositionAuthorizationService, never()).canApproveAdoption(anyLong(), anyLong());
        verify(arrangementService, never()).create(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void approve_noAuthorization_throws403() {
        when(adoptionApplicationMapper.findById(30L)).thenReturn(pendingApplication());
        when(postMapper.findByIdForUpdate(10L)).thenReturn(adoptionPost());
        when(dispositionAuthorizationService.canApproveAdoption(1L, 5L)).thenReturn(false);

        assertThatThrownBy(() -> service.approve(30L, 5L))
                .isInstanceOf(ForbiddenException.class);

        verify(postMapper, never()).reserve(anyLong(), anyLong());
        verify(arrangementService, never()).create(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void approve_reserveCasFails_throws409() {
        when(adoptionApplicationMapper.findById(30L)).thenReturn(pendingApplication());
        when(postMapper.findByIdForUpdate(10L)).thenReturn(adoptionPost());
        when(dispositionAuthorizationService.canApproveAdoption(1L, 5L)).thenReturn(true);
        when(postMapper.reserve(10L, 30L)).thenReturn(0);

        assertThatThrownBy(() -> service.approve(30L, 5L))
                .isInstanceOf(ConflictException.class);

        verify(arrangementService, never()).create(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(adoptionApplicationMapper, never()).rejectOthers(anyLong(), anyLong(), any());
        verify(adoptionApplicationMapper, never()).approve(anyLong());
    }

    @Test
    void approve_success_createsArrangementAndRejectsOthers() {
        when(adoptionApplicationMapper.findById(30L)).thenReturn(pendingApplication());
        when(postMapper.findByIdForUpdate(10L)).thenReturn(adoptionPost());
        when(dispositionAuthorizationService.canApproveAdoption(1L, 5L)).thenReturn(true);
        when(postMapper.reserve(10L, 30L)).thenReturn(1);

        Pet pet = new Pet();
        pet.setId(1L);
        pet.setCurrentCustodianId(8L);
        when(petMapper.findById(1L)).thenReturn(pet);

        when(arrangementService.create(1L, 10L, 30L, 7L, 8L)).thenReturn(40L);
        when(adoptionApplicationMapper.rejectOthers(10L, 30L, "该宠物已被其他申请人匹配")).thenReturn(2);

        ApproveResponse response = service.approve(30L, 5L);

        assertThat(response.arrangementId()).isEqualTo(40L);
        assertThat(response.postStatus()).isEqualTo("RESERVED");
        assertThat(response.rejectedOthers()).isEqualTo(2);

        verify(arrangementService).create(1L, 10L, 30L, 7L, 8L);
        verify(adoptionApplicationMapper).rejectOthers(10L, 30L, "该宠物已被其他申请人匹配");
        verify(adoptionApplicationMapper).approve(30L);
    }
}
