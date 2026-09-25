package org.isolatedareas.helphub.config;

import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlywayRecoveryConfig {
    @Bean
    FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            List<MigrationInfo> failed = Arrays.stream(flyway.info().all())
                    .filter(info -> info.getState().isFailed())
                    .toList();

            if (!failed.isEmpty()) {
                boolean recoverableV9 = failed.size() == 1
                        && failed.getFirst().getVersion() != null
                        && "9".equals(failed.getFirst().getVersion().getVersion())
                        && "refund reconciliation".equals(failed.getFirst().getDescription());
                if (!recoverableV9) {
                    throw new FlywayException("Refusing to repair an unexpected failed migration: " + failed);
                }
                flyway.repair();
            }

            flyway.migrate();
        };
    }
}
