package org.isolatedareas.helphub.config;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class FlywayRecoveryConfigTest {
    @Test
    void repairsOnlyKnownFailedV9BeforeMigrating() {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        MigrationInfo failedV9 = mock(MigrationInfo.class);
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(new MigrationInfo[]{failedV9});
        when(failedV9.getState()).thenReturn(MigrationState.FAILED);
        when(failedV9.getVersion()).thenReturn(MigrationVersion.fromVersion("9"));
        when(failedV9.getDescription()).thenReturn("refund reconciliation");

        new FlywayRecoveryConfig().flywayMigrationStrategy().migrate(flyway);

        InOrder order = inOrder(flyway);
        order.verify(flyway).repair();
        order.verify(flyway).migrate();
    }

    @Test
    void refusesToRepairAnyUnexpectedFailure() {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        MigrationInfo failedV8 = mock(MigrationInfo.class);
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(new MigrationInfo[]{failedV8});
        when(failedV8.getState()).thenReturn(MigrationState.FAILED);
        when(failedV8.getVersion()).thenReturn(MigrationVersion.fromVersion("8"));
        when(failedV8.getDescription()).thenReturn("wechat payments");

        assertThrows(FlywayException.class,
                () -> new FlywayRecoveryConfig().flywayMigrationStrategy().migrate(flyway));

        verify(flyway, never()).repair();
        verify(flyway, never()).migrate();
    }
}
