package com.cholog.bootcamp.ui.dto;

import javax.validation.constraints.NotNull;

public record ChatRequest(@NotNull String question) {

}
