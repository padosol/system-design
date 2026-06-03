package com.padosol.urlshortener.dto

import jakarta.validation.constraints.NotBlank

data class CreateUrlRequest(
    @field:NotBlank
    val longUrl: String,
)
