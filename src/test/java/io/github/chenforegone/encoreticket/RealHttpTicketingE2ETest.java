package io.github.chenforegone.encoreticket;

import io.github.chenforegone.encoreticket.common.constant.RedisConstant;
import io.github.chenforegone.encoreticket.security.token.TokenHasher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 随机端口真实 HTTP → Bearer/Redis → Service → MySQL 全链路。
 * 仅显式启用后运行；必须预建全新空库 encore_ticket_http_e2e_*，结果保留，不执行 clean/drop/flush。
 * SQL 唯一写入边界为给本次 HTTP 注册的账号绑定种子角色，随后通过 HTTP 重新登录。
 * 不模拟认证、Mapper、Service 或支付完成数据；入场窗口使用排片 HTTP 参数配置。
 *
 * @author c1342
 */
@EnabledIfSystemProperty(named = "http.e2e.enabled", matches = "true")
class RealHttpTicketingE2ETest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "E2eOnly2026Safe";
    private static final List<String> TOKENS = new ArrayList<>();
    private static ConfigurableApplicationContext context;
    private static HttpClient client;
    private static JdbcTemplate jdbc;
    private static StringRedisTemplate redis;
    private static String origin;

    @BeforeAll
    static void startIsolatedApplication() throws Exception {
        int mysqlPort = isolatedPort("http.e2e.mysql.port");
        int redisPort = isolatedPort("http.e2e.redis.port");
        assertThat(mysqlPort).isNotEqualTo(redisPort);
        String database = required("http.e2e.mysql.database");
        assertThat(database).matches("encore_ticket_http_e2e_[a-zA-Z0-9_]{1,40}");
        String username = required("http.e2e.mysql.username");
        String mysqlPassword = secret("HTTP_E2E_MYSQL_PASSWORD");
        String redisPassword = secret("HTTP_E2E_REDIS_PASSWORD");
        int redisDatabase = Integer.parseInt(required("http.e2e.redis.database"));
        assertThat(redisDatabase).isBetween(1, 15);
        String url = "jdbc:mysql://127.0.0.1:" + mysqlPort + "/" + database
                + "?connectionTimeZone=UTC&characterEncoding=UTF-8&connectTimeout=3000&socketTimeout=10000";
        // 先于 Spring/Flyway 校验空库，防止迁移或定时任务触及已有业务数据。
        try (var connection = DriverManager.getConnection(url, username, mysqlPassword)) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?")) {
                statement.setString(1, database);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).as("每次运行必须使用新的空隔离库").isZero();
                }
            }
        }
        Map<String, Object> properties = new java.util.HashMap<>();
        properties.put("spring.datasource.url", url);
        properties.put("spring.datasource.username", username);
        properties.put("spring.datasource.password", mysqlPassword);
        properties.put("spring.data.redis.host", "127.0.0.1");
        properties.put("spring.data.redis.port", redisPort);
        properties.put("spring.data.redis.database", redisDatabase);
        properties.put("spring.data.redis.password", redisPassword);
        properties.put("server.port", 0);
        properties.put("server.address", "127.0.0.1");
        var application = new SpringApplication(EncoreTicketBackendApplication.class);
        application.addInitializers(app -> app.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("isolated-http-e2e", properties)));
        context = application.run("--spring.config.location=classpath:/http-e2e.properties",
                "--spring.profiles.active=test");
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        assertThat(port).isPositive();
        origin = "http://127.0.0.1:" + port;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        jdbc = context.getBean(JdbcTemplate.class);
        redis = context.getBean(StringRedisTemplate.class);
        try (var connection = redis.getConnectionFactory().getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
    }

    @AfterAll
    static void closeApplication() throws Exception {
        try {
            if (client != null) {
                // 只注销本类实际拿到的令牌；不扫描 Redis、不清库，失败结果库保留给运行器检查。
                for (String token : TOKENS) {
                    request("POST", "/auth/logout", token, null, null, 200);
                }
            }
        } finally {
            if (client != null) client.close();
            if (context != null) context.close();
        }
    }

    @Test
    void registrationResourcesPurchaseCheckinRefundAndPermissionsOverRealHttp() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String adminName = "admin_" + suffix;
        String staffName = "staff_" + suffix;
        String buyerName = "buyer_" + suffix;
        JsonNode adminRegistration = register(adminName);
        JsonNode staffRegistration = register(staffName);
        JsonNode buyerRegistration = register(buyerName);
        String outsider = token(register("other_" + suffix));
        String buyer = token(buyerRegistration);
        String adminBeforeGrant = token(adminRegistration);
        error("GET", "/admin/movies", adminBeforeGrant, null, null, 403, "FORBIDDEN");
        grantRole(adminRegistration.path("user").path("id").asText(), "ADMIN");
        grantRole(staffRegistration.path("user").path("id").asText(), "CHECKIN_STAFF");
        String admin = login(adminName);
        String staff = login(staffName);
        // 角色仅作夹具准备，旧快照不变；不将此 SQL 当作角色管理/强失效功能验收。
        error("GET", "/admin/movies", adminBeforeGrant, null, null, 403, "FORBIDDEN");
        String buyerLogin = login(buyerName);
        assertThat(get("/auth/me", buyerLogin).path("user").path("id").asText())
                .isEqualTo(buyerRegistration.path("user").path("id").asText());
        assertRedisSession(buyerLogin);
        error("GET", "/tickets", null, null, null, 401, "UNAUTHORIZED");
        error("GET", "/tickets", "unknown_e2e_token", null, null, 401, "UNAUTHORIZED");
        error("POST", "/auth/login", null, null,
                Map.of("username", buyerName, "password", "WrongPassword123"), 401, "UNAUTHORIZED");

        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(ChronoUnit.SECONDS);
        JsonNode movie = request("POST", "/admin/movies", admin, null, Map.of(
                "title", "真实网络电影_" + suffix, "releaseDate", now.toLocalDate().toString(),
                "durationMinutes", 90, "publishAt", now.plusDays(2).toString()), 201);
        String movieId = movie.path("id").asText();
        request("POST", "/admin/movies/" + movieId + "/publish", admin, null, Map.of("version", 0), 204);
        JsonNode hall = request("POST", "/admin/halls", admin, null, Map.of(
                "hallNo", "E2E_" + suffix.substring(0, 20), "hallName", "真实网络影厅",
                "seats", List.of(Map.of("rowNumber", 1, "seatNumber", 1),
                        Map.of("rowNumber", 1, "seatNumber", 2))), 201);
        String screeningId = request("POST", "/admin/screenings", admin, null, Map.of(
                "movieId", movieId, "hallId", hall.path("hallId").asText(), "price", "39.90",
                "startTime", now.plusHours(4).toString(), "saleMode", "IMMEDIATE",
                "admissionOpenTime", now.minusMinutes(1).toString()), 201).path("id").asText();
        JsonNode seats = get("/screenings/" + screeningId + "/seats", null).path("seats");
        assertThat(seats.size()).isEqualTo(2);
        String seat1 = seats.get(0).path("screeningSeatId").asText();
        String seat2 = seats.get(1).path("screeningSeatId").asText();
        assertThat(seat1).matches("[1-9][0-9]*");
        assertThat(get("/screenings/" + screeningId, null).path("status").asText()).isEqualTo("ON_SALE");

        Map<String, Object> orderBody = Map.of("screeningId", screeningId, "screeningSeatIds", List.of(seat1));
        JsonNode order = request("POST", "/orders", buyer, "purchase_1", orderBody, 201);
        String orderId = order.path("orderId").asText();
        assertThat(order.path("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(request("POST", "/orders", buyer, "purchase_1", orderBody, 201)).isEqualTo(order);
        assertThat(get("/tickets", buyer).path("total").asInt()).isZero();
        assertSeat(screeningId, seat1, "HELD");
        error("GET", "/orders/" + orderId, outsider, null, null, 404, "RESOURCE_NOT_FOUND");
        error("POST", "/orders/" + orderId + "/payments", outsider, "forbidden_payment", null,
                404, "RESOURCE_NOT_FOUND");
        paySuccessfully(orderId, buyer, admin, "payment_1");
        assertSeat(screeningId, seat1, "SOLD");
        JsonNode ticket = ticketFor(order, buyer);
        String ticketId = ticket.path("id").asText();
        JsonNode ticketDetail = get("/tickets/" + ticketId, buyer);
        assertThat(ticketDetail.path("status").asText()).isEqualTo("VALID");
        assertThat(ticket.has("ticketCode")).isFalse();
        error("GET", "/tickets/" + ticketId, outsider, null, null, 404, "RESOURCE_NOT_FOUND");
        error("GET", "/tickets/" + ticketId + "/status", admin, null, null, 404, "RESOURCE_NOT_FOUND");
        Map<String, Object> scan = Map.of("ticketCode", ticketDetail.path("ticketCode").asText(),
                "screeningId", screeningId);
        error("POST", "/ticket-verifications", buyer, "buyer_scan", scan, 403, "FORBIDDEN");
        error("POST", "/ticket-verifications", admin, "admin_scan", scan, 403, "FORBIDDEN");
        JsonNode verified = request("POST", "/ticket-verifications", staff, "staff_scan", scan, 200);
        assertThat(verified.path("status").asText()).isEqualTo("USED");
        assertThat(request("POST", "/ticket-verifications", staff, "staff_scan", scan, 200)).isEqualTo(verified);
        error("POST", "/ticket-verifications", staff, "scan_again", scan, 409, "TICKET_ALREADY_USED");
        assertThat(get("/tickets/" + ticketId + "/status", buyer).path("status").asText()).isEqualTo("USED");
        assertThat(get("/admin/ticket-verifications?screeningId=" + screeningId, admin)
                .path("total").asInt()).isEqualTo(1);
        error("POST", "/orders/" + orderId + "/refunds", buyer, "used_refund", null,
                409, "TICKET_ALREADY_USED");

        JsonNode refundable = request("POST", "/orders", buyer, "purchase_2", Map.of(
                "screeningId", screeningId, "screeningSeatIds", List.of(seat2)), 201);
        String refundableId = refundable.path("orderId").asText();
        paySuccessfully(refundableId, buyer, admin, "payment_2");
        String refundableTicket = ticketFor(refundable, buyer).path("id").asText();
        error("POST", "/orders/" + refundableId + "/refunds", outsider, "other_refund", null,
                404, "RESOURCE_NOT_FOUND");
        JsonNode refund = request("POST", "/orders/" + refundableId + "/refunds", buyer, "refund_1", null, 200);
        assertThat(request("POST", "/orders/" + refundableId + "/refunds", buyer, "refund_1", null, 200))
                .isEqualTo(refund);
        awaitRefundSuccess(refundableId, buyer);
        assertThat(get("/orders/" + refundableId, buyer).path("status").asText()).isEqualTo("REFUNDED");
        assertThat(get("/tickets/" + refundableTicket + "/status", buyer).path("status").asText()).isEqualTo("VOID");
        assertSeat(screeningId, seat2, "AVAILABLE");
        request("POST", "/auth/logout", buyerLogin, null, null, 200);
        error("GET", "/auth/me", buyerLogin, null, null, 401, "UNAUTHORIZED");
    }

    private static JsonNode register(String username) throws Exception {
        JsonNode data = request("POST", "/users/register", null, null, Map.of("username", username,
                "password", PASSWORD, "confirmPassword", PASSWORD, "nickname", "网络测试用户"), 201);
        TOKENS.add(data.path("accessToken").asText());
        assertRedisSession(data.path("accessToken").asText());
        return data;
    }

    private static String token(JsonNode registration) {
        return registration.path("accessToken").asText();
    }

    private static String login(String username) throws Exception {
        String token = request("POST", "/auth/login", null, null,
                Map.of("username", username, "password", PASSWORD), 200).path("accessToken").asText();
        TOKENS.add(token);
        return token;
    }

    private static void assertRedisSession(String token) {
        assertThat(token).isNotBlank();
        String key = RedisConstant.USER_TOKEN + new TokenHasher().hash(token);
        String value = redis.opsForValue().get(key);
        assertThat(value != null && !value.contains(token)).as("真实 Redis 仅存摘要键及登录态").isTrue();
        assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 604800L);
    }

    private static void grantRole(String userId, String role) {
        assertThat(jdbc.update("INSERT INTO user_role(id,user_id,role_id,create_time,update_time) "
                + "SELECT ?,?,id,UTC_TIMESTAMP(),UTC_TIMESTAMP() FROM role WHERE role_code=?",
                Long.parseLong(userId) + 100, Long.parseLong(userId), role)).isEqualTo(1);
    }

    private static void paySuccessfully(String orderId, String buyer, String admin, String key) throws Exception {
        JsonNode payment = request("POST", "/orders/" + orderId + "/payments", buyer, key, null, 200);
        assertThat(payment.path("status").asText()).isEqualTo("PENDING");
        String paymentNo = payment.path("paymentNo").asText();
        JsonNode attempts = get("/admin/test/payments/" + paymentNo + "/attempts", admin).path("items");
        assertThat(attempts.size()).isEqualTo(1);
        Map<String, Object> event = Map.of("paymentNo", paymentNo,
                "attemptId", attempts.get(0).path("attemptId").asText(), "eventId", key + "_event", "result", "SUCCESS");
        error("POST", "/admin/test/payments/events", buyer, null, event, 403, "FORBIDDEN");
        request("POST", "/admin/test/payments/events", admin, null, event, 200);
        request("POST", "/admin/test/payments/events", admin, null, event, 200);
        assertThat(get("/orders/" + orderId, buyer).path("status").asText()).isEqualTo("PAID");
        assertThat(get("/orders/" + orderId + "/payments", buyer).path("items").get(0)
                .path("status").asText()).isEqualTo("SUCCESS");
    }

    private static JsonNode ticketFor(JsonNode order, String buyer) throws Exception {
        JsonNode page = get("/tickets?orderNo=" + order.path("orderNo").asText(), buyer);
        assertThat(page.path("total").asInt()).as("重复渠道事件不得重复出票").isEqualTo(1);
        return page.path("items").get(0);
    }

    private static void assertSeat(String screeningId, String seatId, String status) throws Exception {
        for (JsonNode seat : get("/screenings/" + screeningId + "/seats", null).path("seats")) {
            if (seat.path("screeningSeatId").asText().equals(seatId)) {
                assertThat(seat.path("status").asText()).isEqualTo(status);
                return;
            }
        }
        throw new AssertionError("场次座位未出现在真实 HTTP 响应中");
    }

    private static void awaitRefundSuccess(String orderId, String buyer) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        do {
            JsonNode refunds = get("/orders/" + orderId + "/refunds", buyer);
            assertThat(refunds.path("items").size()).isEqualTo(1);
            String status = refunds.path("items").get(0).path("status").asText();
            assertThat(status).isIn("PENDING", "SUCCESS");
            if (status.equals("SUCCESS")) return;
            // 业务异步调度的有界等待，不调用 Service、不更新 SQL 强制完成。
            TimeUnit.MILLISECONDS.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("真实退款调度未在有界期限内完成，不将 REFUNDING 当作退款成功");
    }

    private static JsonNode get(String path, String token) throws Exception {
        return request("GET", path, token, null, null, 200);
    }

    private static void error(String method, String path, String token, String key, Object body,
            int status, String code) throws Exception {
        JsonNode response = exchange(method, path, token, key, body, status);
        assertThat(response.path("code").asText()).isEqualTo(code);
        assertThat(response.path("data").isNull()).isTrue();
    }

    private static JsonNode request(String method, String path, String token, String key, Object body,
            int status) throws Exception {
        return exchange(method, path, token, key, body, status).path("data");
    }

    private static JsonNode exchange(String method, String path, String token, String key, Object body,
            int expectedStatus) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        // 不把请求、响应全文、密码、票码或令牌输出到断言日志。
        assertThat(response.statusCode()).as("%s %s 的 HTTP 状态", method, path).isEqualTo(expectedStatus);
        String requestId = response.headers().firstValue("X-Request-Id").orElse("");
        assertThat(requestId).isNotBlank();
        if (expectedStatus == 204) {
            assertThat(response.body()).isEmpty();
            return JSON.readTree("null");
        }
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        JsonNode json = JSON.readTree(response.body());
        if (expectedStatus >= 400) assertThat(json.path("requestId").asText()).isEqualTo(requestId);
        return json;
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("必须显式设置 " + property);
        return value;
    }

    private static String secret(String environment) {
        String value = System.getenv(environment);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("必须设置环境变量 " + environment);
        return value;
    }

    private static int isolatedPort(String property) {
        int port = Integer.parseInt(required(property));
        assertThat(port).isBetween(1024, 65535).isNotIn(3306, 3308, 6379, 6380);
        return port;
    }
}
