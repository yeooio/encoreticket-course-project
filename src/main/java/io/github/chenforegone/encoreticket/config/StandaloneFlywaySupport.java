package io.github.chenforegone.encoreticket.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

import java.util.Arrays;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 非 Spring 调用方的显式回调注册及本地测试库迁移配置。
 *
 * @author c1342
 */
public final class StandaloneFlywaySupport {
    private static final Pattern LOCAL_TEST_URL = Pattern.compile(
            "jdbc:mysql://127\\.0\\.0\\.1:([1-9][0-9]{0,4})/"
                    + "encore_ticket_flyway_poc_[a-z0-9_]+"
                    + "\\?connectionTimeZone=UTC&characterEncoding=UTF-8");

    private StandaloneFlywaySupport() {
    }

    public static void main(String[] arguments) {
        String command = command(arguments);
        Flyway flyway = localTestConfiguration(System.getenv()).load();
        execute(flyway, command);
    }

    /** 为手工构建的配置注册回调，保留其他回调；不要与 Spring 自动注册重复使用。 */
    public static FluentConfiguration registerCallbacks(FluentConfiguration configuration) {
        if (Arrays.stream(configuration.getCallbacks())
                .noneMatch(ScreeningHistoricalPrecheckCallback.class::isInstance)) {
            var callbacks = Arrays.copyOf(configuration.getCallbacks(),
                    configuration.getCallbacks().length + 1);
            callbacks[callbacks.length - 1] = new ScreeningHistoricalPrecheckCallback();
            configuration.callbacks(callbacks);
        }
        return configuration;
    }

    /** 校验所有输入后构建配置；此方法本身不加载 Flyway 或连接数据库。 */
    public static FluentConfiguration localTestConfiguration(Map<String, String> environment) {
        if (!"true".equals(environment.get("ENCORE_FLYWAY_LOCAL_ENABLED"))) {
            throw new IllegalArgumentException("必须显式设置 ENCORE_FLYWAY_LOCAL_ENABLED=true");
        }
        String url = required(environment, "ENCORE_FLYWAY_LOCAL_URL");
        var matcher = LOCAL_TEST_URL.matcher(url);
        if (!matcher.matches() || Integer.parseInt(matcher.group(1)) > 65535) {
            throw new IllegalArgumentException("仅允许固定参数的 127.0.0.1 专用 Flyway POC 测试库 URL");
        }
        String username = required(environment, "ENCORE_FLYWAY_LOCAL_USER");
        String password = required(environment, "ENCORE_FLYWAY_LOCAL_PASSWORD");
        return registerCallbacks(Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .createSchemas(false)
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .failOnMissingLocations(true));
    }

    public static String command(String[] arguments) {
        if (arguments.length != 1
                || !("validate".equals(arguments[0]) || "migrate".equals(arguments[0]))) {
            throw new IllegalArgumentException("仅接受一个命令：validate 或 migrate");
        }
        return arguments[0];
    }

    /** 只分派受支持的命令，不捕获迁移或预检异常。 */
    public static void execute(Flyway flyway, String command) {
        switch (command(new String[]{command})) {
            case "validate" -> flyway.validate();
            case "migrate" -> flyway.migrate();
            default -> throw new IllegalStateException("未识别的迁移命令");
        }
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少环境变量 " + name);
        }
        return value;
    }
}
