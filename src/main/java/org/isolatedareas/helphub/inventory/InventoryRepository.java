package org.isolatedareas.helphub.inventory;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryRepository {
    private static final String SELECT = """
        SELECT i.id, i.service_point_id, s.name AS service_point_name, s.address AS service_point_address,
          i.sku, i.name, i.category, i.unit, i.unit_price_fen, i.available_quantity, i.reserved_quantity,
          i.available_quantity-i.reserved_quantity AS free_quantity, i.reorder_threshold, i.version, i.updated_at
        FROM inventory_items i JOIN service_points s ON s.id=i.service_point_id
        """;
    private final JdbcClient jdbc;

    public InventoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** In pinyin order, so residents can browse and jump by initial letter. */
    public List<InventoryItemView> list() {
        return jdbc.sql(SELECT + " ORDER BY i.service_point_id").query(mapper()).list().stream()
            .sorted(java.util.Comparator.comparing(InventoryItemView::name, PinyinIndex.ORDER))
            .toList();
    }

    public Optional<InventoryItemView> find(long id) {
        return jdbc.sql(SELECT + " WHERE i.id=:id").param("id", id).query(mapper()).optional();
    }

    public Optional<InventoryItemView> lock(long id) {
        return jdbc.sql(SELECT + " WHERE i.id=:id FOR UPDATE").param("id", id).query(mapper()).optional();
    }

    public int adjust(long id, int delta, long expectedVersion) {
        return jdbc.sql("""
                UPDATE inventory_items SET available_quantity=available_quantity+:delta, version=version+1
                WHERE id=:id AND version=:version AND available_quantity+:delta >= reserved_quantity
                """)
            .param("delta", delta).param("id", id).param("version", expectedVersion).update();
    }

    public int setPrice(long id, int unitPriceFen, long expectedVersion) {
        return jdbc.sql("""
                UPDATE inventory_items SET unit_price_fen=:price, version=version+1
                WHERE id=:id AND version=:version
                """)
            .param("price", unitPriceFen).param("id", id).param("version", expectedVersion).update();
    }

    public void hold(long id, int quantity) {
        int changed = jdbc.sql("""
                UPDATE inventory_items SET reserved_quantity=reserved_quantity+:quantity, version=version+1
                WHERE id=:id AND available_quantity-reserved_quantity >= :quantity
                """)
            .param("quantity", quantity).param("id", id).update();
        if (changed != 1) throw new IllegalStateException("Insufficient free inventory");
    }

    public void release(long id, int quantity) {
        jdbc.sql("""
                UPDATE inventory_items SET reserved_quantity=reserved_quantity-:quantity, version=version+1
                WHERE id=:id AND reserved_quantity >= :quantity
                """)
            .param("quantity", quantity).param("id", id).update();
    }

    public void collect(long id, int quantity) {
        int changed = jdbc.sql("""
                UPDATE inventory_items SET available_quantity=available_quantity-:quantity,
                  reserved_quantity=reserved_quantity-:quantity, version=version+1
                WHERE id=:id AND available_quantity >= :quantity AND reserved_quantity >= :quantity
                """)
            .param("quantity", quantity).param("id", id).update();
        if (changed != 1) throw new IllegalStateException("Inventory reservation is inconsistent");
    }

    private RowMapper<InventoryItemView> mapper() {
        return (rs, rowNum) -> new InventoryItemView(
            rs.getLong("id"), rs.getLong("service_point_id"), rs.getString("service_point_name"),
            rs.getString("service_point_address"), rs.getString("sku"), rs.getString("name"), rs.getString("category"),
            rs.getString("unit"), rs.getInt("unit_price_fen"), rs.getInt("available_quantity"), rs.getInt("reserved_quantity"),
            rs.getInt("free_quantity"), rs.getInt("reorder_threshold"), rs.getLong("version"),
            rs.getTimestamp("updated_at").toInstant());
    }
}
