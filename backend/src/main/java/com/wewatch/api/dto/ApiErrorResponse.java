package com.wewatch.api.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

public record ApiErrorResponse(
	@Schema(description = "When the error was generated, server time")
	Instant timestamp,
	@Schema(description = "HTTP status code, e.g. 404")
	int status,
	@Schema(description = "HTTP status reason phrase, e.g. \"Not Found\"")
	String error,
	@Schema(description = "Human-readable detail message for the specific failure — for request "
		+ "validation failures, this is \"<field>: <validation message>\" for the first failing field")
	String message,
	@Schema(description = "Request URI that produced the error")
	String path
) {
}
