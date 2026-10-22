package com.smartmart.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DeliveryDetailsRequest(
        @NotBlank @Size(max = 120) String fullName,
        @NotBlank @Pattern(regexp = "^[+0-9()\\-\\s]{10,24}$", message = "Enter a valid mobile number") String mobileNumber,
        @NotBlank @Size(max = 500) String address,
        @NotBlank @Size(max = 120) String city,
        @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "Enter a valid 6 digit PIN code") String pinCode,
        @Size(max = 500) String deliveryInstructions) {
}
