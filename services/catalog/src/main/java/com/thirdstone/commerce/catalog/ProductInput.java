package com.thirdstone.commerce.catalog;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** What an administrator sets on a product. The SKU is in the path; stock belongs to inventory. */
public record ProductInput(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 4000) String description,
        @NotBlank @Size(max = 64) String category,
        @NotNull @Min(0) @Max(100_000_000) Long priceCents,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 code") String currency,
        @Size(max = 200) @Pattern(regexp = "^[A-Za-z0-9._/-]*$", message = "must be a plain path") String imageKey,
        @NotNull Boolean active) {
}
