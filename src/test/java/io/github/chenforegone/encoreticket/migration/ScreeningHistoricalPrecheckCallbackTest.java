package io.github.chenforegone.encoreticket.migration;

import io.github.chenforegone.encoreticket.config.ScreeningHistoricalPrecheckCallback;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 不连接数据库的 V20 前置回调契约测试。
 *
 * @author c1342
 */
class ScreeningHistoricalPrecheckCallbackTest {
    private final ScreeningHistoricalPrecheckCallback callback = new ScreeningHistoricalPrecheckCallback();
    private final Context context = mock(Context.class);

    @Test
    void onlySupportsV20BeforeExecution() {
        assertFalse(callback.supports(Event.BEFORE_MIGRATE, context));
        assertFalse(callback.supports(Event.BEFORE_EACH_MIGRATE, context));
        var migration = mock(MigrationInfo.class);
        when(context.getMigrationInfo()).thenReturn(migration);
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion("20"));
        when(migration.getScript()).thenReturn("V20__screening_admission_support.sql");
        assertTrue(callback.supports(Event.BEFORE_EACH_MIGRATE, context));
        assertFalse(callback.supports(Event.AFTER_EACH_MIGRATE, context));
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion("21"));
        assertFalse(callback.supports(Event.BEFORE_EACH_MIGRATE, context));
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion("20"));
        when(migration.getScript()).thenReturn("V20__other.sql");
        assertFalse(callback.supports(Event.BEFORE_EACH_MIGRATE, context));
        verify(context, never()).getConnection();
    }

    @Test
    void invalidHistoryFailsClearlyWithoutWritingOrClosingFlywayConnection() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var rows = mock(ResultSet.class);
        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getLong(1)).thenReturn(1L);

        var failure = assertThrows(FlywayException.class,
                () -> callback.handle(Event.BEFORE_EACH_MIGRATE, context));
        assertEquals("V20__screening_admission_support.sql: screening historical schedule precheck failed",
                failure.getMessage());
        verify(rows).close();
        verify(statement).close();
        verify(connection).createStatement();
        verifyNoMoreInteractions(connection);
        verify(statement).executeQuery(anyString());
        verifyNoMoreInteractions(statement);
    }

    @Test
    void validHistoryAllowsOriginalMigrationToRun() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var rows = mock(ResultSet.class);
        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getLong(1)).thenReturn(0L);

        assertDoesNotThrow(() -> callback.handle(Event.BEFORE_EACH_MIGRATE, context));
        verify(rows).close();
        verify(statement).close();
        verify(connection, never()).close();
    }

    @Test
    void queryFailurePreservesCauseInsteadOfMisreportingInvalidHistory() throws Exception {
        var connection = mock(Connection.class);
        when(context.getConnection()).thenReturn(connection);
        var cause = new SQLException("连接异常", "08006");
        when(connection.createStatement()).thenThrow(cause);

        var failure = assertThrows(FlywayException.class,
                () -> callback.handle(Event.BEFORE_EACH_MIGRATE, context));
        assertTrue(failure.getMessage().contains("screening historical schedule precheck query failed"));
        assertSame(cause, failure.getCause());
    }
}
