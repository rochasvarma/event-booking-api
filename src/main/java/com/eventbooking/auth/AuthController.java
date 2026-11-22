package com.eventbooking.auth;

import com.eventbooking.common.ApiException;
import com.eventbooking.security.AuthUser;
import com.eventbooking.security.JwtService;
import com.eventbooking.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record RegisterRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 6, max = 72) String password,
            @NotBlank @Pattern(regexp = "(?i)ORGANIZER|CUSTOMER", message = "must be ORGANIZER or CUSTOMER") String role) {}

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    public record AuthResponse(String token, String tokenType, long expiresIn, AuthUser user) {}

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest r) {
        String email = r.email().trim().toLowerCase(Locale.ROOT);
        if (users.findByEmail(email).isPresent()) {
            throw ApiException.conflict("Email already registered");
        }
        var u = users.insert(r.name().trim(), email, encoder.encode(r.password()), r.role().toUpperCase(Locale.ROOT));
        return respond(new AuthUser(u.id(), u.email(), u.name(), u.role()));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest r) {
        var u = users.findByEmail(r.email().trim().toLowerCase(Locale.ROOT))
                .filter(x -> encoder.matches(r.password(), x.passwordHash()))
                .orElseThrow(() -> ApiException.unauthorized("Invalid email or password"));
        return respond(new AuthUser(u.id(), u.email(), u.name(), u.role()));
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal AuthUser user) {
        return Map.of("user", user);
    }

    private AuthResponse respond(AuthUser user) {
        return new AuthResponse(jwt.issue(user), "Bearer", jwt.ttlSeconds(), user);
    }
}
