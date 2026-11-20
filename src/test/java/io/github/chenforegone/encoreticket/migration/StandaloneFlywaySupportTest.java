package io.github.chenforegone.encoreticket.migration;

import io.github.chenforegone.encoreticket.config.ScreeningHistoricalPrecheckCallback;
import io.github.chenforegone.encoreticket.config.StandaloneFlywaySupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 手工配置和独立入口的离线契约测试，不启动 Spring 或访问数据库。
 *
 * @author c1342
 */
class StandaloneFlywaySupportTest {
    @Test
    void manualRegistrationPreservesOtherCallbacksAndIsIdempotent() {
        var other = mock(Callback.class);
        var configuration = Flyway.configure().callbacks(other);
        assertSame(configuration, StandaloneFlywaySupport.registerCallbacks(configuration));
        StandaloneFlywaySupport.registerCallbacks(configuration);
        assertEquals(2, configuration.getCallbacks().length);
        assertSame(other, configuration.getCallbacks()[0]);
        assertInstanceOf(ScreeningHistoricalPrecheckCallback.class, configuration.getCallbacks()[1]);
    }

    @Test
    void manuallyRegisteredCallbackRejectsInvalidHistory() throws Exception {
        var configuration = StandaloneFlywaySupport.registerCallbacks(Flyway.configure());
        var callback = configuration.getCallbacks()[0];
        var context = mock(Context.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var rows = mock(ResultSet.class);
        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getLong(1)).thenReturn(1L);

        assertThrows(FlywayException.class, () -> callback.handle(Event.BEFORE_EACH_MIGRATE, context));
        verify(rows).close();
        verify(statement).close();
        verify(connection, never()).close();
    }

    @Test
    void localConfigurationRegistersCallbackAndKeepsSafetyOptions() {
        var configuration = StandaloneFlywaySupport.localTestConfiguration(environment());
        assertEquals(1, configuration.getCallbacks().length);
        assertInstanceOf(ScreeningHistoricalPrecheckCallback.class, configuration.getCallbacks()[0]);
        assertEquals("classpath:db/migration", configuration.getLocations()[0].getDescriptor());
        assertFalse(configuration.isBaselineOnMigrate());
        assertFalse(configuration.isCreateSchemas());
        assertTrue(configuration.isCleanDisabled());
        assertTrue(configuration.isValidateOnMigrate());
        assertTrue(configuration.isFailOnMissingLocations());
    }

    @Test
    void missingOrBlankEnvironmentValuesFailClosed() {
        for (String key : environment().keySet()) {
            var values = environment();
            values.remove(key);
            assertThrows(IllegalArgumentException.class,
                    () -> StandaloneFlywaySupport.localTestConfiguration(values), key);
            values.put(key, " ");
            assertThrows(IllegalArgumentException.class,
                    () -> StandaloneFlywaySupport.localTestConfiguration(values), key);
        }
        var values = environment();
        values.put("ENCORE_FLYWAY_LOCAL_ENABLED", "TRUE");
        assertThrows(IllegalArgumentException.class,
                () -> StandaloneFlywaySupport.localTestConfiguration(values));
    }

    @Test
    void rejectsBusinessRemoteMultiHostAndUrlOptionBypasses() {
        for (String url : new String[]{
                "jdbc:mysql://127.0.0.1:13384/encore_ticket?connectionTimeZone=UTC&characterEncoding=UTF-8",
                "jdbc:mysql://db.example:13384/encore_ticket_flyway_poc_cli?connectionTimeZone=UTC&characterEncoding=UTF-8",
                "jdbc:mysql://127.0.0.1:65536/encore_ticket_flyway_poc_cli?connectionTimeZone=UTC&characterEncoding=UTF-8",
                "jdbc:mysql://127.0.0.1:0/encore_ticket_flyway_poc_cli?connectionTimeZone=UTC&characterEncoding=UTF-8",
                "jdbc:mysql://127.0.0.1,db.example:13384/encore_ticket_flyway_poc_cli?connectionTimeZone=UTC&characterEncoding=UTF-8",
                "jdbc:mysql://127.0.0.1:13384/encore_ticket_flyway_poc_%63li?connectionTimeZone=UTC&characterEncoding=UTF-8",
                environment().get("ENCORE_FLYWAY_LOCAL_URL") + "&socketFactory=custom.Factory",
                "jdbc:mysql://127.0.0.1:13384/encore_ticket_flyway_poc_cli"
        }) {
            var values = environment();
            values.put("ENCORE_FLYWAY_LOCAL_URL", url);
            assertThrows(IllegalArgumentException.class,
                    () -> StandaloneFlywaySupport.localTestConfiguration(values));
        }
    }

    @Test
    void onlyDispatchesValidateAndMigrateAndPropagatesFailure() {
        var flyway = mock(Flyway.class);
        StandaloneFlywaySupport.execute(flyway, "validate");
        verify(flyway).validate();
        verify(flyway, never()).migrate();
        StandaloneFlywaySupport.execute(flyway, "migrate");
        verify(flyway).migrate();
        var failure = new FlywayException("前置预检失败");
        doThrow(failure).when(flyway).migrate();
        assertSame(failure, assertThrows(FlywayException.class,
                () -> StandaloneFlywaySupport.execute(flyway, "migrate")));
        verify(flyway, times(2)).migrate();
        verifyNoMoreInteractions(flyway);
    }

    @Test
    void rejectsUnsupportedCommandsBeforeLoadingConfiguration() {
        for (String[] arguments : new String[][]{
                {}, {"repair"}, {"clean"}, {"baseline"}, {"info"}, {"migrate", "validate"}, {"--help"}
        }) {
            assertThrows(IllegalArgumentException.class, () -> StandaloneFlywaySupport.main(arguments));
        }
        var flyway = mock(Flyway.class);
        assertThrows(IllegalArgumentException.class, () -> StandaloneFlywaySupport.execute(flyway, "repair"));
        verifyNoInteractions(flyway);
    }

    private Map<String, String> environment() {
        return new HashMap<>(Map.of(
                "ENCORE_FLYWAY_LOCAL_ENABLED", "true",
                "ENCORE_FLYWAY_LOCAL_URL", "jdbc:mysql://127.0.0.1:13384/encore_ticket_flyway_poc_cli?connectionTimeZone=UTC&characterEncoding=UTF-8",
                "ENCORE_FLYWAY_LOCAL_USER", "isolated_test_user",
                "ENCORE_FLYWAY_LOCAL_PASSWORD", "test-only-placeholder"));
    }
}
