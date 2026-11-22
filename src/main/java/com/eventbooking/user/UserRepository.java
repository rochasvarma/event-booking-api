package com.eventbooking.user;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class UserRepository {

    public record User(long id, String name, String email, String passwordHash, String role, OffsetDateTime createdAt) {}

    private static final RowMapper<User> MAPPER = (rs, i) -> new User(
            rs.getLong("id"), rs.getString("name"), rs.getString("email"),
            rs.getString("password_hash"), rs.getString("role"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public User insert(String name, String email, String passwordHash, String role) {
        return jdbc.sql("""
                        INSERT INTO users (name, email, password_hash, role)
                        VALUES (:name, :email, :hash, :role)
                        RETURNING *""")
                .param("name", name).param("email", email).param("hash", passwordHash).param("role", role)
                .query(MAPPER).single();
    }

    public Optional<User> findByEmail(String email) {
        return jdbc.sql("SELECT * FROM users WHERE email = :email").param("email", email).query(MAPPER).optional();
    }

    public Optional<User> findById(long id) {
        return jdbc.sql("SELECT * FROM users WHERE id = :id").param("id", id).query(MAPPER).optional();
    }
}
