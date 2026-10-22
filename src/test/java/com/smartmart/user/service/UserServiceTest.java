package com.smartmart.user.service;

import com.smartmart.user.entity.Role;
import com.smartmart.user.entity.User;
import com.smartmart.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    @Test
    void register_success() {
        String name = "Alice";
        String email = "ALICE@Example.COM";
        String password = "secret";

        when(userRepository.existsByEmail(email)).thenReturn(false);
        when(passwordEncoder.encode(password)).thenReturn("encoded-secret");

        User saved = User.builder()
                .id(1L)
                .name(name)
                .email(email.toLowerCase())
                .password("encoded-secret")
                .role(Role.SHOPPER)
                .build();

        when(userRepository.save(any(User.class))).thenReturn(saved);

        User result = userService.register(name, email, password);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getEmail()).isEqualTo(email.toLowerCase());
        assertThat(result.getName()).isEqualTo(name);
        assertThat(result.getRole()).isEqualTo(Role.SHOPPER);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User toSave = captor.getValue();
        assertThat(toSave.getEmail()).isEqualTo(email.toLowerCase());
        assertThat(toSave.getPassword()).isEqualTo("encoded-secret");

        verify(passwordEncoder).encode(password);
    }

    @Test
    void register_duplicateEmail_throws() {
        when(userRepository.existsByEmail("bob@example.com")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> userService.register("Bob", "bob@example.com", "pw"));
        assertThat(ex.getMessage()).isEqualTo("Email already exists");

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void findByEmail_delegates_and_lowercases() {
        User u = User.builder().id(2L).email("jane@example.com").name("Jane").password("x").role(Role.SHOPPER).build();
        when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(u));

        Optional<User> got = userService.findByEmail("JANE@Example.com");
        assertThat(got).isPresent().contains(u);

        verify(userRepository).findByEmail("jane@example.com");
    }

    @Test
    void existsByEmail_delegates_and_lowercases() {
        when(userRepository.existsByEmail("u@example.com")).thenReturn(true);

        boolean exists = userService.existsByEmail("U@EXAMPLE.com");
        assertThat(exists).isTrue();

        verify(userRepository).existsByEmail("u@example.com");
    }
}
