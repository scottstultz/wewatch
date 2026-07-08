package com.wewatch.api.dto;

import jakarta.validation.constraints.NotNull;

import com.wewatch.api.model.Rating;

public record TitleRatingRequest(@NotNull Rating rating) {
}
