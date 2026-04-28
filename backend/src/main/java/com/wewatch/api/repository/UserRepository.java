package com.wewatch.api.repository;

import java.util.Optional;

import com.wewatch.api.model.User;

public interface UserRepository {

	User create(User user);

	Optional<User> findById(Long id);

	Optional<User> findByEmail(String email);
}
