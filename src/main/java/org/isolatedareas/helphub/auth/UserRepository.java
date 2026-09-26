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

    /** Password hash of an enabled staff account, if it has one. */
    public Optional<String> staffPasswordHash(String account) {
        return jdbc.sql("""
                SELECT password_hash FROM users
                WHERE phone = :account AND enabled = TRUE AND role IN ('OPERATOR','ADMIN')
                  AND password_hash IS NOT NULL
                """)
            .param("account", account).query(String.class).optional();
    }

    /** Creates a staff account or resets its name and password; never touches residents. */
    public UserAccount upsertStaff(String account, String displayName, String passwordHash) {
        int updated = jdbc.sql("""
                UPDATE users SET display_name = :name, password_hash = :hash, enabled = TRUE
                WHERE phone = :account AND role IN ('OPERATOR','ADMIN')
                """)
            .param("name", displayName).param("hash", passwordHash).param("account", account).update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO users (phone, display_name, role, enabled, password_hash)
                    VALUES (:account, :name, 'OPERATOR', TRUE, :hash)
                    """)
                .param("account", account).param("name", displayName).param("hash", passwordHash).update();
        }
        return findByPhone(account).orElseThrow();
    }

    public java.util.List<UserAccount> staff() {
        return jdbc.sql("""
                SELECT id, phone, display_name, role, enabled FROM users
                WHERE role IN ('OPERATOR','ADMIN') AND phone IS NOT NULL ORDER BY id
                """)
            .query((rs, rowNum) -> new UserAccount(rs.getLong("id"), rs.getString("phone"),
                rs.getString("display_name"), Role.valueOf(rs.getString("role")), rs.getBoolean("enabled")))
            .list();
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

