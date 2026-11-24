package com.pethelp.post;

import com.pethelp.common.exception.BadRequestException;
import com.pethelp.common.exception.ConflictException;
import com.pethelp.common.exception.ForbiddenException;
import com.pethelp.pet.entity.Pet;
import com.pethelp.pet.mapper.PetMapper;
import com.pethelp.pet.service.DispositionAuthorizationService;
import com.pethelp.post.dto.CreatePostRequest;
import com.pethelp.post.dto.PostResponse;
import com.pethelp.post.dto.UpdatePostRequest;
import com.pethelp.post.entity.PetPost;
import com.pethelp.post.mapper.PostMapper;
import com.pethelp.post.service.PostService;
import com.pethelp.post.service.impl.PostServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostServiceImplTest {

    private PetMapper petMapper;
    private PostMapper postMapper;
    private DispositionAuthorizationService dispositionAuthorizationService;
    private PostService service;

    @BeforeEach
    void setUp() {
        petMapper = mock(PetMapper.class);
        postMapper = mock(PostMapper.class);
        dispositionAuthorizationService = mock(DispositionAuthorizationService.class);
        service = new PostServiceImpl(petMapper, postMapper, dispositionAuthorizationService);
    }

    @Test
    void create_adoption_autoOpen() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setStatus("ACTIVE");
        pet.setSpecies("CAT");
        pet.setGeoHash("wx4g0");
        pet.setCity("北京");
        pet.setDistrict("朝阳区");
        when(petMapper.findById(1L)).thenReturn(pet);
        when(dispositionAuthorizationService.canPublishAdoption(1L, 5L)).thenReturn(true);
        doAnswer(inv -> {
            PetPost p = inv.getArgument(0);
            p.setId(10L);
            return 1;
        }).when(postMapper).insert(any(PetPost.class));

        PostResponse response = service.create(5L, new CreatePostRequest(1L, "ADOPTION", "寻领养", "乖巧亲人"));

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.status()).isEqualTo("OPEN");
        ArgumentCaptor<PetPost> captor = ArgumentCaptor.forClass(PetPost.class);
        verify(postMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("OPEN");
        assertThat(captor.getValue().getVerifiedAt()).isNotNull();
    }

    @Test
    void create_adoption_noAuthorization_throws403() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setStatus("ACTIVE");
        when(petMapper.findById(1L)).thenReturn(pet);
        when(dispositionAuthorizationService.canPublishAdoption(1L, 5L)).thenReturn(false);

        assertThatThrownBy(() -> service.create(5L, new CreatePostRequest(1L, "ADOPTION", "寻领养", null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void create_petNotActive_throws409() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setStatus("DRAFT");
        when(petMapper.findById(1L)).thenReturn(pet);

        assertThatThrownBy(() -> service.create(5L, new CreatePostRequest(1L, "ADOPTION", "寻领养", null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void create_blankTitle_throws400() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setStatus("ACTIVE");
        when(petMapper.findById(1L)).thenReturn(pet);

        assertThatThrownBy(() -> service.create(5L, new CreatePostRequest(1L, "ADOPTION", "  ", null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void create_invalidPostType_throws400() {
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setStatus("ACTIVE");
        when(petMapper.findById(1L)).thenReturn(pet);

        assertThatThrownBy(() -> service.create(5L, new CreatePostRequest(1L, "FOSTER", "寻领养", null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void update_notOwner_throws403() {
        PetPost post = new PetPost();
        post.setId(1L);
        post.setPublisherId(99L);
        when(postMapper.findById(1L)).thenReturn(post);

        assertThatThrownBy(() -> service.update(1L, 5L, new UpdatePostRequest("新标题", "新描述")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void update_reserved_throws409() {
        PetPost post = new PetPost();
        post.setId(1L);
        post.setPublisherId(5L);
        post.setStatus("RESERVED");
        when(postMapper.findById(1L)).thenReturn(post);

        assertThatThrownBy(() -> service.update(1L, 5L, new UpdatePostRequest("新标题", "新描述")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void close_notOwner_throws403() {
        PetPost post = new PetPost();
        post.setId(1L);
        post.setPublisherId(99L);
        when(postMapper.findById(1L)).thenReturn(post);

        assertThatThrownBy(() -> service.close(1L, 5L))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void cancel_completed_throws409() {
        PetPost post = new PetPost();
        post.setId(1L);
        post.setPublisherId(5L);
        post.setStatus("COMPLETED");
        when(postMapper.findById(1L)).thenReturn(post);

        assertThatThrownBy(() -> service.cancel(1L, 5L))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void convertToAdoption_notVerified_throws409() {
        PetPost post = new PetPost();
        post.setId(1L);
        post.setPetId(1L);
        post.setPublisherId(5L);
        post.setPostType("FINDING");
        post.setStatus("OPEN");
        when(postMapper.findById(1L)).thenReturn(post);
        Pet pet = new Pet();
        pet.setId(1L);
        pet.setOwnershipStatus("OWNED");
        pet.setAuthorizationStatus("UNAUTHORIZED");
        when(petMapper.findById(1L)).thenReturn(pet);

        assertThatThrownBy(() -> service.convertToAdoption(1L, 5L))
                .isInstanceOf(ConflictException.class);
    }
}
