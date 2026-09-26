package org.isolatedareas.helphub.automation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The disabled "自动调度系统" account (V15) recorded as the actor of automatic decisions. */
@Component
public class SystemActor {
    private final JdbcClient jdbc;
    private volatile Long id;

    public SystemActor(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long id() {
        Long cached = id;
        if (cached != null) return cached;
        long found = jdbc.sql("""
                SELECT MIN(id) FROM users
                WHERE display_name = '自动调度系统' AND role = 'OPERATOR' AND enabled = FALSE
                """).query(Long.class).single();
        id = found;
        return found;
    }
}
