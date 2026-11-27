package com.pethelp.admin;

import com.pethelp.admin.dto.AdminImageResponse;
import com.pethelp.admin.dto.RejectPostRequest;
import com.pethelp.admin.dto.UserBanResponse;
import com.pethelp.admin.service.impl.AdminServiceImpl;
import com.pethelp.common.exception.BadRequestException;
import com.pethelp.common.exception.ConflictException;
import com.pethelp.common.exception.ForbiddenException;
import com.pethelp.common.exception.NotFoundException;
import com.pethelp.pet.dto.ExactLocationResponse;
import com.pethelp.pet.entity.PetImage;
import com.pethelp.pet.mapper.PetImageMapper;
import com.pethelp.pet.service.LocationAccessService;
import com.pethelp.post.dto.PostResponse;
import com.pethelp.post.entity.PetPost;
import com.pethelp.post.mapper.PostMapper;
import com.pethelp.security.CurrentUser;
import com.pethelp.user.entity.User;
import com.pethelp.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminServiceImplTest {

    private PostMapper postMapper;
    private PetImageMapper petImageMapper;
    private UserMapper userMapper;
    private LocationAccessService locationAccessService;
    private AdminServiceImpl service;

    @BeforeEach
    void setUp() {
        postMapper = mock(PostMapper.class);
        petImageMapper = mock(PetImageMapper.class);
        userMapper = mock(UserMapper.class);
        locationAccessService = mock(LocationAccessService.class);
        service = new AdminServiceImpl(postMapper, petImageMapper, userMapper, locationAccessService);
    }

    @Test
    void takeDownPost_byOps_ok() {
        PetPost post = new PetPost();
        post.setId(1L);
        post.setPetId(10L);
        post.setPublisherId(5L);
        post.setPostType("FINDING");
        post.setTitle("t");
        post.setDescription("d");
        post.setStatus("OPEN");
        when(postMapper.findById(1L)).thenReturn(post);
        when(postMapper.takeDown(1L)).thenReturn(1);

        CurrentUser ops = new CurrentUser(2L, List.of("ops"));
        PostResponse resp = service.takeDownPost(ops, 1L);

        assertThat(resp.id()).isEqualTo(1L);
        assertThat(resp.status()).isEqualTo("CLOSED");
    }

    @Test
    void takeDownPost_byPlainUser_403() {
        CurrentUser plain = new CurrentUser(3L, List.of("user"));
        assertThatThrownBy(() -> service.takeDownPost(plain, 1L))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void rejectPost_blankReason_400() {
        PetPost post = new PetPost();
        post.setId(1L);
        post.setStatus("OPEN");
        when(postMapper.findById(1L)).thenReturn(post);
        CurrentUser ops = new CurrentUser(2L, List.of("ops"));

        assertThatThrownBy(() -> service.rejectPost(ops, 1L, new RejectPostRequest("   ")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectPost_postNotFound_404() {
        when(postMapper.findById(99L)).thenReturn(null);
        CurrentUser ops = new CurrentUser(2L, List.of("ops"));

        assertThatThrownBy(() -> service.rejectPost(ops, 99L, new RejectPostRequest("reason")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void verifyPost_ok() {
        PetPost post = new PetPost();
        post.setId(1L);
        post.setStatus("REJECTED");
        when(postMapper.findById(1L)).thenReturn(post);
        when(postMapper.verify(1L)).thenReturn(1);

        CurrentUser ops = new CurrentUser(2L, List.of("ops"));
        PostResponse resp = service.verifyPost(ops, 1L);

        assertThat(resp.id()).isEqualTo(1L);
        assertThat(resp.status()).isEqualTo("OPEN");
    }

    @Test
    void banUser_byAdmin_ok() {
        User user = new User();
        user.setId(5L);
        user.setStatus("ACTIVE");
        when(userMapper.findById(5L)).thenReturn(user);
        when(userMapper.updateStatus(5L, "BANNED")).thenReturn(1);

        CurrentUser admin = new CurrentUser(1L, List.of("admin"));
        UserBanResponse resp = service.banUser(admin, 5L);

        assertThat(resp.userId()).isEqualTo(5L);
        assertThat(resp.status()).isEqualTo("BANNED");
    }

    @Test
    void banUser_byOps_403() {
        CurrentUser ops = new CurrentUser(2L, List.of("ops"));
        assertThatThrownBy(() -> service.banUser(ops, 5L))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void banUser_alreadyBanned_409() {
        User user = new User();
        user.setId(5L);
        user.setStatus("BANNED");
        when(userMapper.findById(5L)).thenReturn(user);

        CurrentUser admin = new CurrentUser(1L, List.of("admin"));
        assertThatThrownBy(() -> service.banUser(admin, 5L))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void approveImage_ok() {
        PetImage image = new PetImage();
        image.setId(1L);
        image.setPetId(10L);
        image.setUploaderId(5L);
        image.setFileType("PHOTO");
        image.setReviewStatus("PENDING");
        when(petImageMapper.findById(1L)).thenReturn(image);
        when(petImageMapper.updateReviewStatus(1L, "APPROVED")).thenReturn(1);

        CurrentUser ops = new CurrentUser(2L, List.of("ops"));
        AdminImageResponse resp = service.approveImage(ops, 1L);

        assertThat(resp.id()).isEqualTo(1L);
        assertThat(resp.reviewStatus()).isEqualTo("APPROVED");
    }

    @Test
    void rejectImage_ok() {
        PetImage image = new PetImage();
        image.setId(1L);
        image.setPetId(10L);
        image.setUploaderId(5L);
        image.setFileType("PHOTO");
        image.setReviewStatus("PENDING");
        when(petImageMapper.findById(1L)).thenReturn(image);
        when(petImageMapper.updateReviewStatus(1L, "REJECTED")).thenReturn(1);

        CurrentUser ops = new CurrentUser(2L, List.of("ops"));
        AdminImageResponse resp = service.rejectImage(ops, 1L);

        assertThat(resp.id()).isEqualTo(1L);
        assertThat(resp.reviewStatus()).isEqualTo("REJECTED");
    }

    @Test
    void listPosts_validPagination_ok() {
        PetPost post = new PetPost();
        post.setId(1L);
        post.setStatus("OPEN");
        when(postMapper.listForAdmin("OPEN", 0, 20)).thenReturn(List.of(post));

        CurrentUser ops = new CurrentUser(2L, List.of("ops"));
        List<PostResponse> list = service.listPosts(ops, "OPEN", 1, 20);

        assertThat(list).hasSize(1);
    }

    @Test
    void getPetExactLocation_byOps_ok() {
        when(locationAccessService.getExactLocationForOps(10L, 2L))
                .thenReturn(new ExactLocationResponse(121.5, 31.2));

        CurrentUser ops = new CurrentUser(2L, List.of("ops"));
        ExactLocationResponse resp = service.getPetExactLocation(ops, 10L);

        assertThat(resp.lng()).isEqualTo(121.5);
        assertThat(resp.lat()).isEqualTo(31.2);
    }
}
