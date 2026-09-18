package com.smartmart.auth.service;

import com.smartmart.auth.dto.AuthResponse;
import com.smartmart.auth.dto.LoginRequest;
import com.smartmart.auth.dto.RegisterRequest;
import com.smartmart.security.JwtUtil;
import com.smartmart.user.entity.User;
import com.smartmart.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;

    public AuthResponse register(RegisterRequest request) {
        User user = userService.register(request.getName(), request.getEmail(), request.getPassword());
        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole().name());
        return buildAuthResponse(user, token);
    }

    public AuthResponse login(LoginRequest request) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
            );
            User user = (User) authentication.getPrincipal();
            String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole().name());
            return buildAuthResponse(user, token);
        } catch (AuthenticationException e) {
            throw new IllegalArgumentException("Invalid email or password");
        }
    }

    private AuthResponse buildAuthResponse(User user, String token) {
        return AuthResponse.builder()
                .token(token)
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }
}