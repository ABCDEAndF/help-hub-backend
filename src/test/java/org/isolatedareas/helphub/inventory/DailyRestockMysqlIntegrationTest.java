package org.isolatedareas.helphub.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.isolatedareas.helphub.audit.AuditService;
import org.isolatedareas.helphub.automation.SystemActor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Restocks against a real MySQL database migrated with the production migrations
 * (set REPRO_MYSQL_URL, REPRO_MYSQL_USER and REPRO_MYSQL_PASSWORD).
 */
@EnabledIfEnvironmentVariable(named = "REPRO_MYSQL_URL", matches = ".+")
class DailyRestockMysqlIntegrationTest {
    @Test
    void refillsOnlyFreeItemsBelowTheirThresholdAndKeepsReservations() {
        var ds = new DriverManagerDataSource(System.getenv("REPRO_MYSQL_URL"),
            System.getenv("REPRO_MYSQL_USER"), System.getenv("REPRO_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        var jdbc = JdbcClient.create(ds);
        var restock = new DailyRestock(jdbc, new AuditService(jdbc, new ObjectMapper()), new SystemActor(jdbc), true);

        long rice = id(jdbc, "QP-XY-RICE-5KG");   // threshold 15
        long water = id(jdbc, "QP-XY-WATER-12");  // threshold 12
        // Rice runs low with 4 held for residents; water still has plenty.
        jdbc.sql("UPDATE inventory_items SET available_quantity=10, reserved_quantity=4 WHERE id=:id").param("id", rice).update();
        jdbc.sql("UPDATE inventory_items SET available_quantity=40, reserved_quantity=0 WHERE id=:id").param("id", water).update();
        int low = jdbc.sql("""
                SELECT COUNT(*) FROM inventory_items
                WHERE unit_price_fen=0 AND reorder_threshold>0 AND available_quantity-reserved_quantity < reorder_threshold
                """).query(Integer.class).single();

        assertThat(restock.restock()).isEqualTo(low).isPositive();

        assertThat(quantities(jdbc, rice)).containsExactly(4 + 15 * DailyRestock.RESTOCK_MULTIPLE, 4);
        assertThat(quantities(jdbc, water)).containsExactly(40, 0);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action='INVENTORY_RESTOCKED'").query(Integer.class).single())
            .isEqualTo(low);
        // Nothing is below its threshold any more, so a second run (or another instance) does nothing.
        assertThat(restock.restock()).isZero();
    }

    private static long id(JdbcClient jdbc, String sku) {
        return jdbc.sql("SELECT id FROM inventory_items WHERE sku=:sku").param("sku", sku).query(Long.class).single();
    }

    private static int[] quantities(JdbcClient jdbc, long id) {
        return jdbc.sql("SELECT available_quantity, reserved_quantity FROM inventory_items WHERE id=:id").param("id", id)
            .query((rs, n) -> new int[] {rs.getInt(1), rs.getInt(2)}).single();
    }
}
