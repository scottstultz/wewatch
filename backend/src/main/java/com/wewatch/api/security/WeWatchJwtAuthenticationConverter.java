package com.wewatch.api.security;

import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import com.wewatch.api.model.User;
import com.wewatch.api.service.UserService;

public class WeWatchJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	private final UserService userService;

	public WeWatchJwtAuthenticationConverter(UserService userService) {
		this.userService = userService;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		Long userId;
		try {
			userId = Long.parseLong(jwt.getSubject());
		} catch (NumberFormatException e) {
			throw new InvalidBearerTokenException("Invalid subject claim", e);
		}
		User user;
		try {
			user = userService.findById(userId);
		} catch (NoSuchElementException e) {
			throw new InvalidBearerTokenException("Unknown user", e);
		}
		return new UsernamePasswordAuthenticationToken(user, null, List.of());
	}
}
