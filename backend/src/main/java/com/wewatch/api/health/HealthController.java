package com.wewatch.api.health;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/health")
@Tag(name = "Health", description = "Liveness check")
public class HealthController {

	@GetMapping
	@Operation(summary = "Check liveness", description = "Returns UP if the service is running. No authentication required.")
	@ApiResponse(responseCode = "200", description = "Service is up")
	@SecurityRequirements
	public Map<String, String> health() {
		return Map.of("status", "UP");
	}

}
