package com.smartmart.user.controller;

import com.smartmart.user.dto.DeliveryProfileResponse;
import com.smartmart.user.entity.User;
import com.smartmart.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me/delivery-profile")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SHOPPER')")
public class DeliveryProfileController {
    private final UserRepository users;

    @GetMapping
    public DeliveryProfileResponse get(@AuthenticationPrincipal User user) {
        return DeliveryProfileResponse.from(users.findById(user.getId()).orElseThrow());
    }
}
