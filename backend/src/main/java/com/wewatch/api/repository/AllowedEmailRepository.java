package com.wewatch.api.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wewatch.api.model.AllowedEmail;

public interface AllowedEmailRepository extends JpaRepository<AllowedEmail, Long> {

	boolean existsByEmail(String email);
}
