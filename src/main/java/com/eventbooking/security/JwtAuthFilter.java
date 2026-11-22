package com.eventbooking.security;

import com.eventbooking.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserRepository users;
    private final boolean baseline;

    public JwtAuthFilter(JwtService jwtService, UserRepository users,
                         @Value("${app.booking.mode}") String mode) {
        this.jwtService = jwtService;
        this.users = users;
        this.baseline = "baseline".equalsIgnoreCase(mode);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            Optional<AuthUser> parsed = jwtService.parse(header.substring(7));
            if (parsed.isPresent() && baseline) {
                // Baseline: re-load the user from the DB on every request (a common first
                // implementation). Costs one extra DB round trip + pool connection per call.
                parsed = users.findById(parsed.get().id())
                        .map(u -> new AuthUser(u.id(), u.email(), u.name(), u.role()));
            }
            parsed.ifPresent(user -> {
                var auth = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(req, res);
    }
}
