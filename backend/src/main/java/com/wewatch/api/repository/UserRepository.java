package com.wewatch.api.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wewatch.api.model.User;

public interface UserRepository extends JpaRepository<User, Long> {

	// Case-insensitive by design (#345): writes store the canonical lowercase form, but a row
	// inserted by hand can still carry mixed case, and LOWER(email) = LOWER(?) is served by the
	// uq_users_email_lower index added in V25.
	Optional<User> findByEmailIgnoreCase(String email);

	Optional<User> findByProviderAndProviderId(String provider, String providerId);
}
