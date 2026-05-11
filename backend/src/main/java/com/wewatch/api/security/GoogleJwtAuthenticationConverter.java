package com.wewatch.api.security;

import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import com.wewatch.api.model.User;
import com.wewatch.api.service.UserService;

public class GoogleJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	private final UserService userService;

	public GoogleJwtAuthenticationConverter(UserService userService) {
		this.userService = userService;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		String sub = jwt.getSubject();
		String email = jwt.getClaimAsString("email");
		String name = jwt.getClaimAsString("name");
		User user = userService.findOrCreateByGoogleIdentity(sub, email, name);
		return new UsernamePasswordAuthenticationToken(user, null, List.of());
	}
}
