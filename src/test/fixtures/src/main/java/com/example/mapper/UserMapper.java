package com.example.mapper;

import com.example.entity.User;
import java.util.List;

/**
 * User mapper interface — used by mb2dsl for mode refinement.
 */
public interface UserMapper {
    User findById(Long id);
    List<User> findByGender(String gender, int limit, int offset);
    Long insertUser(User user);
    void deleteUser(Long id);
}
