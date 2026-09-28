package org.isolatedareas.helphub.inventory;

import java.util.List;
import java.util.Map;
import org.isolatedareas.helphub.audit.AuditService;
import org.isolatedareas.helphub.automation.SystemActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The morning delivery to the service points: every free item whose free stock has fallen below
 * its reorder threshold is brought back to {@link #RESTOCK_MULTIPLE} times that threshold, so
 * residents' requests keep being met over the months. Each restock is audited. The update is
 * conditional and sets an absolute level, so several instances running it at once restock once.
 */
@Component
public class DailyRestock {
    private static final Logger log = LoggerFactory.getLogger(DailyRestock.class);
    static final int RESTOCK_MULTIPLE = 5;

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final SystemActor system;
    private final boolean enabled;

    public DailyRestock(JdbcClient jdbc, AuditService audit, SystemActor system,
                        @Value("${app.inventory.daily-restock:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.system = system;
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Shanghai")
    public void run() {
        if (!enabled) return;
        try {
            int restocked = restock();
            if (restocked > 0) log.info("Daily restock refilled {} items", restocked);
        } catch (RuntimeException failure) {
            log.warn("Daily restock failed; it runs again tomorrow", failure);
        }
    }

    /** Refills every free item below its threshold; returns how many were refilled. */
    public int restock() {
        List<Low> low = jdbc.sql("""
                SELECT id, available_quantity, reserved_quantity, reorder_threshold FROM inventory_items
                WHERE unit_price_fen=0 AND reorder_threshold>0 AND available_quantity-reserved_quantity < reorder_threshold
                """)
            .query((rs, n) -> new Low(rs.getLong("id"), rs.getInt("available_quantity"),
                rs.getInt("reserved_quantity"), rs.getInt("reorder_threshold"))).list();
        int restocked = 0;
        for (Low item : low) {
            int changed = jdbc.sql("""
                    UPDATE inventory_items
                    SET available_quantity=reserved_quantity+reorder_threshold*:multiple, version=version+1
                    WHERE id=:id AND unit_price_fen=0 AND available_quantity-reserved_quantity < reorder_threshold
                    """).param("multiple", RESTOCK_MULTIPLE).param("id", item.id()).update();
            if (changed != 1) continue;
            int after = jdbc.sql("SELECT available_quantity FROM inventory_items WHERE id=:id")
                .param("id", item.id()).query(Integer.class).single();
            audit.record(system.id(), "INVENTORY_RESTOCKED", "INVENTORY_ITEM", item.id(),
                Map.of("availableQuantity", item.available(), "reservedQuantity", item.reserved()),
                Map.of("availableQuantity", after, "reservedQuantity", item.reserved()));
            restocked++;
        }
        return restocked;
    }

    private record Low(long id, int available, int reserved, int threshold) {
    }
}
