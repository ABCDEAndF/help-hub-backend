package org.isolatedareas.helphub.auth;

import java.util.Optional;
import org.isolatedareas.helphub.domain.Role;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByPhone(String phone) {
        return jdbc.sql("""
                SELECT id, phone, display_name, role, enabled
                FROM users WHERE phone = :phone
                """)
            .param("phone", phone)
            .query((rs, rowNum) -> new UserAccount(
                rs.getLong("id"),
                rs.getString("phone"),
                rs.getString("display_name"),
                Role.valueOf(rs.getString("role")),
                rs.getBoolean("enabled")))
            .optional();
    }

    public Optional<UserAccount> findById(long id) {
        return jdbc.sql("""
                SELECT id, phone, display_name, role, enabled
                FROM users WHERE id = :id
                """)
            .param("id", id)
            .query((rs, rowNum) -> new UserAccount(
                rs.getLong("id"),
                rs.getString("phone"),
                rs.getString("display_name"),
                Role.valueOf(rs.getString("role")),
                rs.getBoolean("enabled")))
            .optional();
    }

    public UserAccount upsertWechatUser(String openId, String displayName) {
        jdbc.sql("""
                INSERT INTO users (wechat_open_id, display_name, role)
                VALUES (:openId, :displayName, 'RESIDENT')
                ON DUPLICATE KEY UPDATE display_name = VALUES(display_name)
                """)
            .param("openId", openId)
            .param("displayName", displayName)
            .update();
        return jdbc.sql("""
                SELECT id, phone, display_name, role, enabled
                FROM users WHERE wechat_open_id = :openId
                """)
            .param("openId", openId)
            .query((rs, rowNum) -> new UserAccount(
                rs.getLong("id"),
                rs.getString("phone"),
                rs.getString("display_name"),
                Role.valueOf(rs.getString("role")),
                rs.getBoolean("enabled")))
            .single();
    }
}

