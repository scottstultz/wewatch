package com.wewatch.api.security;

import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import com.wewatch.api.model.User;
import com.wewatch.api.service.UserService;

public class WeWatchJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	private final UserService userService;

	public WeWatchJwtAuthenticationConverter(UserService userService) {
		this.userService = userService;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		Long userId = Long.parseLong(jwt.getSubject());
		User user = userService.findById(userId);
		return new UsernamePasswordAuthenticationToken(user, null, List.of());
	}
}
