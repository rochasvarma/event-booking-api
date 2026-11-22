package com.eventbooking.config;

import com.eventbooking.common.GlobalExceptionHandler;
import com.eventbooking.security.JwtAuthFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtFilter, ObjectMapper mapper)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/", "/actuator/health", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        // Browsing events is public
                        .requestMatchers(HttpMethod.GET, "/api/events", "/api/events/*").permitAll()
                        // Role-based access control at the URL level...
                        .requestMatchers("/api/organizer/**").hasRole("ORGANIZER")
                        .requestMatchers(HttpMethod.POST, "/api/events").hasRole("ORGANIZER")
                        .requestMatchers(HttpMethod.PATCH, "/api/events/*").hasRole("ORGANIZER")
                        .requestMatchers(HttpMethod.DELETE, "/api/events/*").hasRole("ORGANIZER")
                        .requestMatchers(HttpMethod.POST, "/api/events/*/bookings").hasRole("CUSTOMER")
                        .requestMatchers("/api/bookings/**").hasRole("CUSTOMER")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                write(res, mapper, HttpStatus.UNAUTHORIZED, "Missing or invalid token"))
                        .accessDeniedHandler((req, res, ex) ->
                                write(res, mapper, HttpStatus.FORBIDDEN, "Your role is not allowed to do this")))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void write(HttpServletResponse res, ObjectMapper mapper, HttpStatus status, String msg)
            throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), GlobalExceptionHandler.body(status, msg));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
