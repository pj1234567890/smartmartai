package com.smartmart.order.dto;

import com.smartmart.order.entity.DeliveryStatus;
import jakarta.validation.constraints.NotNull;

public record DeliveryStatusUpdateRequest(@NotNull DeliveryStatus status) {
}
