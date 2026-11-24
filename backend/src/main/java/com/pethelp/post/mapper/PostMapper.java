package com.pethelp.post.mapper;

import com.pethelp.post.entity.PetPost;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PostMapper {

    int insert(PetPost post);

    PetPost findById(@Param("id") Long id);

    List<PetPost> list(@Param("postType") String postType, @Param("species") String species,
                       @Param("offset") int offset, @Param("limit") int limit);

    PetPost findByIdForUpdate(@Param("id") Long id);

    int reserve(@Param("id") Long id, @Param("applicationId") Long applicationId);

    int reopen(@Param("id") Long id);

    int markCompleted(@Param("id") Long id);

    int update(@Param("id") Long id, @Param("publisherId") Long publisherId,
               @Param("title") String title, @Param("description") String description);

    int close(@Param("id") Long id, @Param("publisherId") Long publisherId);

    int cancel(@Param("id") Long id, @Param("publisherId") Long publisherId);
}
