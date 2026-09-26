package org.isolatedareas.helphub.requests;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.isolatedareas.helphub.domain.FulfillmentMethod;
import org.isolatedareas.helphub.domain.RequestStatus;
import org.isolatedareas.helphub.domain.Urgency;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SupplyRequestRepository {
    private static final String SELECT = """
        SELECT r.id, r.resident_id, r.resident_seq, u.display_name AS resident_name, r.category, r.inventory_item_id,
          r.item_description, r.quantity, r.urgency, r.fulfillment_method, r.status, r.latitude, r.longitude,
          r.approximate_address, r.accessibility_notes, r.decision_note, r.preferred_start, r.preferred_end,
          r.assigned_service_point_id, r.assigned_cart_id, sp.name AS assigned_service_point_name,
          mc.name AS assigned_cart_name, r.created_at, r.updated_at
        FROM supply_requests r JOIN users u ON u.id = r.resident_id
        LEFT JOIN service_points sp ON sp.id = r.assigned_service_point_id
        LEFT JOIN mobile_carts mc ON mc.id = r.assigned_cart_id
        """;
    private final JdbcClient jdbc;

    public SupplyRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long residentId, CreateSupplyRequest input) {
        org.springframework.jdbc.support.GeneratedKeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        // Serialises a resident's own submissions so their request numbers never collide.
        jdbc.sql("SELECT id FROM users WHERE id=:residentId FOR UPDATE").param("residentId", residentId)
            .query(Long.class).optional();
        jdbc.sql("""
                INSERT INTO supply_requests
                  (resident_id, resident_seq, category, inventory_item_id, item_description, quantity, urgency,
                   fulfillment_method, latitude, longitude, approximate_address, accessibility_notes,
                   preferred_start, preferred_end)
                SELECT :residentId, COALESCE(MAX(resident_seq), 0) + 1, :category, :inventoryItemId, :description,
                  :quantity, :urgency, :fulfillmentMethod, :latitude, :longitude, :address, :notes,
                  :preferredStart, :preferredEnd
                FROM supply_requests WHERE resident_id = :residentId
                """)
            .param("residentId", residentId)
            .param("category", input.category())
            .param("inventoryItemId", input.inventoryItemId())
            .param("description", input.itemDescription())
            .param("quantity", input.quantity())
            .param("urgency", input.urgency().name())
            .param("fulfillmentMethod", input.fulfillmentMethod().name())
            .param("latitude", input.latitude())
            .param("longitude", input.longitude())
            .param("address", input.approximateAddress())
            .param("notes", input.accessibilityNotes())
            .param("preferredStart", timestamp(input.preferredStart()))
            .param("preferredEnd", timestamp(input.preferredEnd()))
            .update(keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("Request ID was not generated");
        return key.longValue();
    }

    public Optional<SupplyRequestView> findById(long id) {
        return jdbc.sql(SELECT + " WHERE r.id=:id")
            .param("id", id).query(mapper()).optional();
    }

    /** A resident's request by the number they see (their own 1, 2, 3 ...). */
    public Optional<SupplyRequestView> findOwnedByNumber(int number, long residentId) {
        return jdbc.sql(SELECT + " WHERE r.resident_seq=:number AND r.resident_id=:residentId")
            .param("number", number).param("residentId", residentId).query(mapper()).optional();
    }

    public Optional<SupplyRequestView> findOwned(long id, long residentId) {
        return jdbc.sql(SELECT + " WHERE r.id=:id AND r.resident_id=:residentId")
            .param("id", id).param("residentId", residentId).query(mapper()).optional();
    }

    public List<SupplyRequestView> findByResident(long residentId, int limit, int offset) {
        return jdbc.sql(SELECT + " WHERE r.resident_id=:residentId ORDER BY r.created_at DESC LIMIT :limit OFFSET :offset")
            .param("residentId", residentId).param("limit", limit).param("offset", offset)
            .query(mapper()).list();
    }

    public List<SupplyRequestView> findForOperations(RequestStatus status, int limit, int offset) {
        if (status == null) {
            return jdbc.sql(SELECT + " ORDER BY FIELD(r.urgency,'CRITICAL','HIGH','NORMAL','LOW'), r.created_at LIMIT :limit OFFSET :offset")
                .param("limit", limit).param("offset", offset).query(mapper()).list();
        }
        return jdbc.sql(SELECT + " WHERE r.status=:status ORDER BY FIELD(r.urgency,'CRITICAL','HIGH','NORMAL','LOW'), r.created_at LIMIT :limit OFFSET :offset")
            .param("status", status.name()).param("limit", limit).param("offset", offset)
            .query(mapper()).list();
    }

    public int transition(long id, long expectedVersion, RequestStatus from, RequestStatus to, long actorId,
                          Long servicePointId, Long cartId) {
        return jdbc.sql("""
                UPDATE supply_requests SET status=:toStatus, reviewed_by=:actorId,
                  reviewed_at=CURRENT_TIMESTAMP(3), assigned_service_point_id=COALESCE(:pointId, assigned_service_point_id),
                  assigned_cart_id=COALESCE(:cartId, assigned_cart_id),
                  fulfilled_at=CASE WHEN :toStatus='FULFILLED' THEN CURRENT_TIMESTAMP(3) ELSE fulfilled_at END,
                  version=version+1
                WHERE id=:id AND status=:fromStatus AND version=:version
                """)
            .param("toStatus", to.name())
            .param("actorId", actorId)
            .param("pointId", servicePointId)
            .param("cartId", cartId)
            .param("id", id)
            .param("fromStatus", from.name())
            .param("version", expectedVersion)
            .update();
    }

    public void recordDecision(long id, String note) {
        jdbc.sql("UPDATE supply_requests SET decision_note=:note WHERE id=:id")
            .param("note", note).param("id", id).update();
    }

    public long version(long id) {
        return jdbc.sql("SELECT version FROM supply_requests WHERE id=:id")
            .param("id", id).query(Long.class).optional().orElseThrow();
    }

    private RowMapper<SupplyRequestView> mapper() {
        return (rs, rowNum) -> new SupplyRequestView(
            rs.getLong("id"), rs.getLong("resident_id"), rs.getInt("resident_seq"), rs.getString("resident_name"),
            rs.getString("category"), nullableLong(rs, "inventory_item_id"), rs.getString("item_description"),
            rs.getInt("quantity"),
            Urgency.valueOf(rs.getString("urgency")), FulfillmentMethod.valueOf(rs.getString("fulfillment_method")),
            RequestStatus.valueOf(rs.getString("status")),
            rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getString("approximate_address"),
            rs.getString("accessibility_notes"), rs.getString("decision_note"), instant(rs.getTimestamp("preferred_start")),
            instant(rs.getTimestamp("preferred_end")), nullableLong(rs, "assigned_service_point_id"),
            nullableLong(rs, "assigned_cart_id"), rs.getString("assigned_service_point_name"),
            rs.getString("assigned_cart_name"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}

