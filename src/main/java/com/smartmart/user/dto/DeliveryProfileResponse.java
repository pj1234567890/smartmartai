package com.smartmart.user.dto;

import com.smartmart.user.entity.User;

public record DeliveryProfileResponse(String fullName, String mobileNumber, String address,
                                      String city, String pinCode, String deliveryInstructions) {
    public static DeliveryProfileResponse from(User user) {
        return new DeliveryProfileResponse(user.getName(), user.getMobileNumber(), user.getDeliveryAddress(),
                user.getDeliveryCity(), user.getDeliveryPinCode(), user.getDeliveryInstructions());
    }
}
