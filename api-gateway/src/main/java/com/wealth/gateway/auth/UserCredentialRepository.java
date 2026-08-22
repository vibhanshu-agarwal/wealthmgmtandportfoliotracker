package com.wealth.gateway.auth;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Optional;
import java.util.UUID;

/**
 * Plain NamedParameterJdbcTemplate access to the users/user_credentials tables owned by
 * portfolio-service (Req 2.6 — api-gateway reads/writes but defines no migrations).
 *
 * <p>Deliberately NOT a {@code @Repository} (component-scanned) bean: this class is only
 * instantiated by {@link GatewayAuthDataConfig}'s explicit {@code @Bean} method, which is
 * gated on {@code spring.datasource.url} being present. Component-scanning this class
 * unconditionally would make Spring eagerly try to instantiate it as a singleton in every
 * profile's ApplicationContext (aws/azure included), failing with an unsatisfied
 * {@code NamedParameterJdbcTemplate} dependency wherever no datasource is configured.
 */
public class UserCredentialRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public UserCredentialRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CredentialRow(String userId, String email, String name, String passwordHash, boolean readOnly) {}

    public Optional<CredentialRow> findByEmailIgnoreCase(String email) {
        String sql = """
                SELECT u.id AS user_id, u.name AS name, u.read_only AS read_only,
                       c.email AS email, c.password_hash AS password_hash
                  FROM user_credentials c
                  JOIN users u ON u.id = c.user_id
                 WHERE lower(c.email) = lower(:email)
                """;
        var params = new MapSqlParameterSource("email", email);
        var rows = jdbc.query(sql, params, (rs, rowNum) -> new CredentialRow(
                rs.getString("user_id"),
                rs.getString("email"),
                rs.getString("name"),
                rs.getString("password_hash"),
                rs.getBoolean("read_only")));
        return rows.stream().findFirst();
    }

    public void insertUser(UUID id, String email, String name) {
        String sql = "INSERT INTO users (id, email, name, read_only) VALUES (:id, :email, :name, false)";
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("email", email)
                .addValue("name", name));
    }

    public void insertCredential(UUID userId, String email, String hash) {
        String sql = "INSERT INTO user_credentials (user_id, email, password_hash) "
                + "VALUES (:userId, :email, :hash)";
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("email", email)
                .addValue("hash", hash));
    }

    public void insertPortfolio(UUID id, UUID userId) {
        String sql = "INSERT INTO portfolios (id, user_id) VALUES (:id, :userId)";
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId.toString()));
    }
}
