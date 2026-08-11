package com.example.mapper;

import com.example.entity.User;
import org.apache.ibatis.annotations.*;

/**
 * Pure annotation mapper — tests annotation SQL extraction.
 */
@Mapper
public interface AnnotationOnlyMapper {

    @Select("SELECT id, display_name, gender, avatar FROM users WHERE id = #{id}")
    User selectById(@Param("id") Long id);

    @Insert("INSERT INTO users (display_name, gender) VALUES (#{displayName}, #{gender})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    @Update("UPDATE users SET gender = #{gender} WHERE id = #{id}")
    int updateGender(@Param("id") Long id, @Param("gender") String gender);

    @Delete("DELETE FROM users WHERE id = #{id}")
    void deleteById(@Param("id") Long id);
}
