package com.thirdstone.commerce.order;

import com.thirdstone.commerce.platform.events.Address;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A checkout. The client names what it wants and the total it showed the
 * customer; it never says what anything costs — the price comes from the order
 * service's own copy of the catalog.
 */
public record PlaceOrderRequest(
        @NotEmpty @Size(max = 20) List<@Valid @NotNull LineRequest> lines,
        @Valid @NotNull ShippingAddress shippingAddress,
        @NotBlank @Pattern(regexp = "^tok_[a-z0-9_]{1,40}$", message = "must be a payment token") String paymentMethod,
        @NotNull @Min(0) Long expectedTotalCents) {

    public record LineRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9-]{0,39}$", message = "is not a product code") String sku,
            @Min(1) @Max(20) int quantity) {
    }

    public record ShippingAddress(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 200) String line1,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 20) String postalCode,
            @NotBlank @Pattern(regexp = "^[A-Z]{2}$", message = "must be a two-letter country code") String country) {

        Address toAddress() {
            return new Address(name.strip(), line1.strip(), city.strip(), postalCode.strip(), country);
        }
    }
}
