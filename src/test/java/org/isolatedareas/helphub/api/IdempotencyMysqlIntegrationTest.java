package org.isolatedareas.helphub.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the production migrations against a real, empty MySQL database (set REPRO_MYSQL_URL,
 * REPRO_MYSQL_USER and REPRO_MYSQL_PASSWORD). MySQL 5.7 with explicit_defaults_for_timestamp=OFF
 * used to reset expiry columns on UPDATE, which broke idempotent replays in production.
 */
@EnabledIfEnvironmentVariable(named = "REPRO_MYSQL_URL", matches = ".+")
class IdempotencyMysqlIntegrationTest {
    record Resp(long id) {}
    record Req(String a) {}

    @Test
    void migratedSchemaKeepsExpiryAndReplaysOriginalResponse() {
        var ds = new DriverManagerDataSource(System.getenv("REPRO_MYSQL_URL"),
            System.getenv("REPRO_MYSQL_USER"), System.getenv("REPRO_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        var jdbc = JdbcClient.create(ds);

        var autoUpdatedExpiries = jdbc.sql("""
                SELECT CONCAT(table_name, '.', column_name) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND column_name = 'expires_at'
                  AND LOWER(extra) LIKE '%on update%'
                """).query(String.class).list();
        assertThat(autoUpdatedExpiries).isEmpty();

        long userId = jdbc.sql("SELECT id FROM users WHERE phone = '13800000000'").query(Long.class).single();
        var service = new IdempotencyService(jdbc, new ObjectMapper());
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var executions = new AtomicInteger();
        String key = "prod-request-" + UUID.randomUUID();

        Resp first = tx.execute(s -> service.execute(key, userId, "OP", new Req("x"), Resp.class,
            () -> new Resp(executions.incrementAndGet())));
        Resp replay = tx.execute(s -> service.execute(key, userId, "OP", new Req("x"), Resp.class,
            () -> new Resp(executions.incrementAndGet())));

        assertThat(replay).isEqualTo(first);
        assertThat(executions).hasValue(1);
        assertThat(jdbc.sql("""
                SELECT expires_at > CURRENT_TIMESTAMP(3) + INTERVAL 23 HOUR
                FROM idempotency_records WHERE idempotency_key = :key
                """).param("key", key).query(Boolean.class).single()).isTrue();
    }
}
