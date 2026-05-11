package com.wewatch.api.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wewatch.api.model.User;

public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByEmail(String email);

	Optional<User> findByProviderAndProviderId(String provider, String providerId);

	@Query("SELECT u FROM User u WHERE (:email IS NULL OR u.email = :email) AND (:displayName IS NULL OR u.displayName = :displayName) ORDER BY u.id")
	List<User> findByFilters(@Param("email") String email, @Param("displayName") String displayName);
}
