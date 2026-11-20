package io.github.chenforegone.encoreticket.config;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

import java.sql.SQLException;

/**
 * 在 V20 执行前拒绝异常历史排片，保留已应用迁移的原文及校验和。
 *
 * @author c1342
 */
@Component
public class ScreeningHistoricalPrecheckCallback implements Callback {
    private static final String SCRIPT = "V20__screening_admission_support.sql";
    private static final String PRECHECK_SQL = """
            SELECT COUNT(*) FROM (
                SELECT s.id
                FROM screening s
                WHERE s.end_time <= s.start_time
                   OR s.sale_end_time >= s.start_time
                   OR s.sale_start_time >= s.sale_end_time
                UNION ALL
                SELECT earlier.id
                FROM screening earlier
                JOIN screening later ON later.hall_id = earlier.hall_id
                    AND later.start_time >= earlier.start_time
                    AND later.id <> earlier.id
                WHERE earlier.end_time + INTERVAL 15 MINUTE > later.start_time
            ) invalid_rows
            """;

    @Override
    public boolean supports(Event event, Context context) {
        if (event != Event.BEFORE_EACH_MIGRATE) {
            return false;
        }
        var migration = context.getMigrationInfo();
        return migration != null
                && MigrationVersion.fromVersion("20").equals(migration.getVersion())
                && SCRIPT.equals(migration.getScript());
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        try (var statement = context.getConnection().createStatement();
             var rows = statement.executeQuery(PRECHECK_SQL)) {
            if (rows.next() && rows.getLong(1) > 0) {
                // MySQL 8.4 不支持 PREPARE SIGNAL；必须在历史 SQL 执行前明确拒绝。
                throw new FlywayException(SCRIPT + ": screening historical schedule precheck failed");
            }
        } catch (SQLException failure) {
            throw new FlywayException(SCRIPT + ": screening historical schedule precheck query failed", failure);
        }
    }

    @Override
    public String getCallbackName() {
        return "screeningHistoricalPrecheck";
    }
}
