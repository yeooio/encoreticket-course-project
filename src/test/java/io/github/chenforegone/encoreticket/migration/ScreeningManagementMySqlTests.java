package io.github.chenforegone.encoreticket.migration;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import io.github.chenforegone.encoreticket.common.exception.ApplicationException;
import io.github.chenforegone.encoreticket.common.exception.GlobalExceptionHandler;
import io.github.chenforegone.encoreticket.config.MybatisPlusMetaObjectHandler;
import io.github.chenforegone.encoreticket.converter.MovieConverter;
import io.github.chenforegone.encoreticket.converter.ScreeningConverter;
import io.github.chenforegone.encoreticket.mapper.HallMapper;
import io.github.chenforegone.encoreticket.mapper.MovieMapper;
import io.github.chenforegone.encoreticket.mapper.OrderMapper;
import io.github.chenforegone.encoreticket.mapper.ScreeningMapper;
import io.github.chenforegone.encoreticket.mapper.ScreeningLifecycleMapper;
import io.github.chenforegone.encoreticket.mapper.ScreeningQueryMapper;
import io.github.chenforegone.encoreticket.mapper.ScreeningSeatMapper;
import io.github.chenforegone.encoreticket.mapper.SeatMapper;
import io.github.chenforegone.encoreticket.model.dto.CreateScreeningRequest;
import io.github.chenforegone.encoreticket.model.dto.AdminScreeningQueryRequest;
import io.github.chenforegone.encoreticket.model.dto.PublicScreeningQueryRequest;
import io.github.chenforegone.encoreticket.model.dto.ScreeningVersionRequest;
import io.github.chenforegone.encoreticket.model.dto.UpdateMovieRequest;
import io.github.chenforegone.encoreticket.model.dto.UpdateScreeningRequest;
import io.github.chenforegone.encoreticket.model.enums.ScreeningSaleMode;
import io.github.chenforegone.encoreticket.model.vo.ScreeningCreatedVO;
import io.github.chenforegone.encoreticket.service.rules.HallScheduleGuard;
import io.github.chenforegone.encoreticket.service.MovieService;
import io.github.chenforegone.encoreticket.service.transaction.OrderExpirationProcessor;
import io.github.chenforegone.encoreticket.service.impl.OrderExpirationService;
import io.github.chenforegone.encoreticket.service.ScreeningService;
import io.github.chenforegone.encoreticket.service.impl.MovieServiceImpl;
import io.github.chenforegone.encoreticket.service.impl.ScreeningServiceImpl;
import io.github.chenforegone.encoreticket.scheduler.ScreeningLifecycleScheduler;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises screening services through real Spring transactions and MySQL mapper statements.
 * The opt-in URL must reference a disposable schema and may never use the business port 3308.
 * No application scan, Redis connection, or background scheduler is started.
 *
 * @author c1342
 */
@EnabledIfSystemProperty(named = "flyway.poc.enabled", matches = "true")
class ScreeningManagementMySqlTests {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private DriverManagerDataSource control;
    private DriverManagerDataSource data;
    private JdbcTemplate jdbc;
    private String schema;
    private boolean schemaCreated;
    private AnnotationConfigApplicationContext context;
    private ScreeningService commands;
    private MovieService movies;
    private final AdjustableClock clock = new AdjustableClock(NOW);

    @BeforeEach
    void createDisposableDatabaseAndTransactionalServices() throws Exception {
        String url = System.getProperty("flyway.poc.url", "");
        if (!url.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("A disposable MySQL URL is required");
        }
        URI uri = URI.create(url.substring(5));
        if (uri.getUserInfo() != null || uri.getPort() == 3308 || uri.getPath() == null
                || !uri.getPath().matches("/encore_ticket_flyway_poc_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Only disposable schemas outside business port 3308 are accepted");
        }
        String password = System.getenv("FLYWAY_POC_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Set FLYWAY_POC_PASSWORD");
        }
        String username = System.getProperty("flyway.poc.username", "encore_ticket");
        control = new DriverManagerDataSource(url, username, password);
        schema = "encore_ticket_flyway_poc_sc_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = control.getConnection(); Statement statement = connection.createStatement()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertEquals(8, connection.getMetaData().getDatabaseMajorVersion());
            assertEquals(4, connection.getMetaData().getDatabaseMinorVersion());
            statement.executeUpdate("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
            schemaCreated = true;
        }
        data = new DriverManagerDataSource("jdbc:mysql://" + uri.getRawAuthority() + "/" + schema
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()), username, password);
        jdbc = new JdbcTemplate(data);
        Flyway.configure().dataSource(data).locations("classpath:db/migration")
                .cleanDisabled(true).createSchemas(false).load().migrate();
        buildTransactionalContext();
        seedMovie(1, 0);
        seedHall(1);
        seedSeat(11, 1, 1, 0);
        seedSeat(12, 1, 2, 0);
        seedSeat(13, 1, 3, 1);
    }

    @AfterEach
    void closeContextAndDropOnlyOwnSchema() throws SQLException {
        if (context != null) {
            context.close();
        }
        if (schemaCreated && schema.matches("encore_ticket_flyway_poc_sc_[a-f0-9]{32}")) {
            try (Connection connection = control.getConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE `" + schema + "`");
            }
        }
    }

    @Test
    void createCommitsCalculatedTimesAndOnlyEnabledSeatSnapshot() {
        assertTrue(AopUtils.isAopProxy(commands), "The service must use the actual transaction interceptor");
        ScreeningCreatedVO created = commands.create(scheduled("1", "1", "2026-10-01T12:00:00+08:00"));

        var detail = commands.getAdmin(created.id());
        assertEquals("WAIT_SALE", detail.status());
        assertEquals(0, detail.version());
        assertEquals("35.00", detail.price());
        assertEquals("CNY", detail.currency());
        assertEquals(OffsetDateTime.parse("2026-10-01T14:00:00+08:00"), detail.endTime());
        assertEquals(OffsetDateTime.parse("2026-10-01T11:45:00+08:00"), detail.saleEndTime());
        assertEquals(120, detail.durationMinutesSnapshot());
        assertEquals(15, detail.turnaroundMinutesSnapshot());
        var seats = commands.getSeats(created.id());
        assertFalse(seats.canPurchase());
        assertEquals(List.of("11", "12"), seats.seats().stream().map(seat -> seat.seatId()).toList());
        assertTrue(seats.seats().stream().allMatch(seat -> seat.status().equals("AVAILABLE")));
    }

    @Test
    void overlapIncludesTurnaroundAndFailureRollsBackAllSharedVersions() {
        commands.create(scheduled("1", "1", "2026-10-01T12:00:00+08:00"));

        assertCode("RESOURCE_CONFLICT", () ->
                commands.create(scheduled("1", "1", "2026-10-01T14:14:59+08:00")));
        assertEquals(1, movies.getAdmin("1").version());
        assertEquals(0L, scalar("SELECT schedule_version FROM hall WHERE id = 1"));
        assertEquals(1, commands.listAdmin(adminQuery()).total());

        ScreeningCreatedVO adjacent = commands.create(scheduled("1", "1", "2026-10-01T14:15:00+08:00"));
        assertEquals("WAIT_SALE", commands.getAdmin(adjacent.id()).status());
        assertEquals(2, commands.listAdmin(adminQuery()).total());
        assertEquals(0L, scalar("SELECT schedule_version FROM hall WHERE id = 1"));
    }

    @Test
    void legacyTurnaroundBeyondMysqlMaximumDateStillBlocksOverlappingScreenings() {
        jdbc.update("""
                INSERT INTO screening (id,movie_id,hall_id,price,start_time,end_time,duration_minutes_snapshot,
                    turnaround_minutes_snapshot,admission_open_time,sale_start_time,sale_end_time,status,version,create_time,update_time)
                VALUES (100,1,1,35,'9999-12-31 23:00:00','9999-12-31 23:55:00',55,15,
                    '9999-12-31 22:45:00','2026-10-01 09:00:00','9999-12-31 22:45:00',0,0,UTC_TIMESTAMP(),UTC_TIMESTAMP())
                """);
        jdbc.update("UPDATE movie SET duration_minutes=10 WHERE id=1");

        assertCode("RESOURCE_CONFLICT", () ->
                commands.create(scheduled("1", "1", "9999-12-31T23:30:00+08:00")));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM screening"));
    }

    @Test
    void concurrentDifferentMoviesCannotCommitOverlappingScreeningsInOneHall() throws Exception {
        seedMovie(2, 0);

        var outcomes = race(
                () -> commands.create(scheduled("1", "1", "2026-10-01T12:00:00+08:00")),
                () -> commands.create(scheduled("2", "1", "2026-10-01T13:00:00+08:00")));

        assertOneCommittedOneBusinessConflict(outcomes);
        assertEquals(1, commands.listAdmin(adminQuery()).total());
        assertEquals(0L, scalar("SELECT schedule_version FROM hall WHERE id = 1"));
        assertEquals(1, movies.getAdmin("1").version() + movies.getAdmin("2").version());
        var only = commands.listAdmin(adminQuery()).items().getFirst();
        assertEquals(2, commands.getSeats(only.id()).seats().size());
    }

    @Test
    void concurrentEditsOfDifferentScreeningsCannotMoveBothIntoOneTimeSlot() throws Exception {
        seedMovie(2, 0);
        ScreeningCreatedVO first = commands.create(scheduled("1", "1", "2026-10-01T12:00:00+08:00"));
        ScreeningCreatedVO second = commands.create(scheduled("2", "1", "2026-10-01T16:00:00+08:00"));

        var firstEdit = new UpdateScreeningRequest("1", "1", "35.00",
                OffsetDateTime.parse("2026-10-01T19:00:00+08:00"),
                OffsetDateTime.parse("2026-10-01T09:00:00+08:00"),
                OffsetDateTime.parse("2026-10-01T18:45:00+08:00"), 0);
        var secondEdit = new UpdateScreeningRequest("2", "1", "35.00",
                firstEdit.startTime(), firstEdit.saleStartTime(), firstEdit.admissionOpenTime(), 0);
        var outcomes = race(
                () -> commands.update(first.id(), firstEdit),
                () -> commands.update(second.id(), secondEdit));

        assertOneCommittedOneBusinessConflict(outcomes);
        var firstAfter = commands.getAdmin(first.id());
        var secondAfter = commands.getAdmin(second.id());
        assertEquals(1, firstAfter.version() + secondAfter.version());
        assertEquals(1, List.of(firstAfter, secondAfter).stream()
                .filter(value -> value.startTime().getHour() == 19).count());
        assertEquals(0L, scalar("SELECT schedule_version FROM hall WHERE id = 1"));
        assertEquals(3, movies.getAdmin("1").version() + movies.getAdmin("2").version());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void eitherScreeningCanMoveFirstWhenAdmissionWindowMovesWithIt(boolean firstWins) {
        seedMovie(2, 0);
        var first = commands.create(scheduled("1", "1", "2026-10-01T12:00:00+08:00"));
        var second = commands.create(scheduled("2", "1", "2026-10-01T16:00:00+08:00"));
        var target = OffsetDateTime.parse("2026-10-01T19:00:00+08:00");
        var winner = firstWins ? first : second;
        var loser = firstWins ? second : first;
        String winnerMovie = firstWins ? "1" : "2";
        String loserMovie = firstWins ? "2" : "1";

        var moved = commands.update(winner.id(), new UpdateScreeningRequest(winnerMovie, "1", "35.00",
                target, OffsetDateTime.parse("2026-10-01T09:00:00+08:00"), target.minusMinutes(15), 0));

        assertEquals(target, moved.startTime());
        assertEquals(target.minusMinutes(15), moved.admissionOpenTime());
        assertEquals(1, moved.version());
        assertCode("RESOURCE_CONFLICT", () -> commands.update(loser.id(), new UpdateScreeningRequest(
                loserMovie, "1", "35.00", target, moved.saleStartTime(), target.minusMinutes(15), 0)));
        assertEquals(0, commands.getAdmin(loser.id()).version());
        assertEquals(2, movies.getAdmin(winnerMovie).version());
        assertEquals(1, movies.getAdmin(loserMovie).version());
    }

    @Test
    void seatRebuildDatabaseFailureRollsBackHallChangeDeletionAndVersions() {
        seedHall(2);
        seedSeat(21, 2, 1, 0);
        ScreeningCreatedVO created = commands.create(scheduled("1", "1", "2026-10-01T12:00:00+08:00"));
        var oldSeatIds = commands.getSeats(created.id()).seats().stream().map(seat -> seat.screeningSeatId()).toList();
        jdbc.execute("""
                CREATE TRIGGER fail_seat_rebuild BEFORE INSERT ON screening_seat FOR EACH ROW
                BEGIN
                    IF NEW.seat_id = 21 THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Injected test-only seat write failure';
                    END IF;
                END
                """);

        assertThrows(DataAccessException.class, () -> commands.update(created.id(),
                update("1", "2", "2026-10-01T12:00:00+08:00", 0)));

        var unchanged = commands.getAdmin(created.id());
        assertEquals("1", unchanged.hallId());
        assertEquals(0, unchanged.version());
        assertEquals(oldSeatIds, commands.getSeats(created.id()).seats().stream()
                .map(seat -> seat.screeningSeatId()).toList());
        assertEquals(1, movies.getAdmin("1").version());
        assertEquals(0L, scalar("SELECT schedule_version FROM hall WHERE id = 1"));
        assertEquals(0L, scalar("SELECT schedule_version FROM hall WHERE id = 2"));

        jdbc.execute("DROP TRIGGER fail_seat_rebuild");
        var changed = commands.update(created.id(), update("1", "2", "2026-10-01T12:00:00+08:00", 0));
        assertEquals("2", changed.hallId());
        assertEquals(1, changed.version());
        assertEquals(List.of("21"), commands.getSeats(created.id()).seats().stream().map(seat -> seat.seatId()).toList());
    }

    @Test
    void offlineMovieCanBeScheduledButCannotBePublishedOrReadPublicly() {
        seedMovie(2, 1);
        ScreeningCreatedVO created = commands.create(scheduled("2", "1", "2026-10-01T12:00:00+08:00"));

        assertEquals("WAIT_SALE", commands.getAdmin(created.id()).status());
        assertEquals(0, commands.listPublic(publicQuery()).total());
        assertCode("RESOURCE_NOT_FOUND", () -> commands.getPublic(created.id()));
        assertCode("RESOURCE_NOT_FOUND", () -> commands.getSeats(created.id()));
        assertCode("MOVIE_NOT_ONLINE", () -> commands.onSale(created.id(), new ScreeningVersionRequest(0)));
        assertEquals(0, commands.getAdmin(created.id()).version());
    }

    @Test
    void allTenHttpEndpointsUseRealPersistenceAndPreserveVersionAndVisibilityContracts() throws Exception {
        MockMvc api = api();
        String location = api.perform(post("/admin/screenings").contentType(APPLICATION_JSON).content("""
                        {"movieId":"1","hallId":"1","price":"35.00",
                         "startTime":"2026-10-01T12:00:00+08:00","saleStartTime":"2026-10-01T09:00:00+08:00"}
                        """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").isNotEmpty())
                .andReturn().getResponse().getHeader("Location");
        assertNotNull(location);
        String publicLocation = location.substring("/admin".length());
        api.perform(get("/admin/screenings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        api.perform(get(location)).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(0));
        api.perform(get("/screenings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].canPurchase").value(false));
        api.perform(get(publicLocation)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currency").value("CNY"));
        api.perform(get(publicLocation + "/seats")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seats.length()").value(2))
                .andExpect(jsonPath("$.data.seats[0].orderId").doesNotExist());
        String edit = """
                {"movieId":"1","hallId":"1","price":"36.50","startTime":"2026-10-01T13:00:00+08:00",
                 "saleStartTime":"2026-10-01T09:00:00+08:00","version":0}
                """;
        api.perform(put(location).contentType(APPLICATION_JSON).content(edit)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1)).andExpect(jsonPath("$.data.price").value("36.50"));
        api.perform(put(location).contentType(APPLICATION_JSON).content(edit)).andExpect(status().isConflict());
        api.perform(post(location + "/on-sale").contentType(APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ON_SALE"))
                .andExpect(jsonPath("$.data.saleStartTime").value("2026-10-01T08:00:00+08:00"));
        api.perform(get(publicLocation)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canPurchase").value(true));
        api.perform(post(location + "/close").contentType(APPLICATION_JSON).content("{\"version\":2}"))
                .andExpect(status().isConflict());
        api.perform(post(location + "/off-sale").contentType(APPLICATION_JSON).content("{\"version\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(3));
        api.perform(get(publicLocation)).andExpect(status().isNotFound());
        api.perform(get(publicLocation + "/seats")).andExpect(status().isNotFound());
        api.perform(put(location).contentType(APPLICATION_JSON).content(edit.replace("\"version\":0", "\"version\":3")))
                .andExpect(status().isConflict());
        api.perform(post(location + "/on-sale").contentType(APPLICATION_JSON).content("{\"version\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(4));
        api.perform(post(location + "/off-sale").contentType(APPLICATION_JSON).content("{\"version\":4}"))
                .andExpect(status().isOk());
        api.perform(post(location + "/close").contentType(APPLICATION_JSON).content("{\"version\":5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.version").value(6));
        api.perform(post(location + "/on-sale").contentType(APPLICATION_JSON).content("{\"version\":6}"))
                .andExpect(status().isConflict());
        api.perform(get("/screenings")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void movieReleaseDateCannotInvalidateExistingOrConcurrentlyCreatedScreening() throws Exception {
        jdbc.update("UPDATE movie SET status = 1 WHERE id = 1");
        var outcomes = race(
                () -> commands.create(scheduled("1", "1", "2026-10-01T12:00:00+08:00")),
                () -> movies.update("1", new UpdateMovieRequest("集成电影1", null,
                        LocalDate.of(2026, 10, 2), 120, null, 0)));

        assertEquals(1, outcomes.stream().filter(value -> value.failure() == null).count());
        assertInstanceOf(ApplicationException.class, outcomes.stream()
                .filter(value -> value.failure() != null).findFirst().orElseThrow().failure());
        var movie = movies.getAdmin("1");
        assertEquals(1, movie.version());
        if (commands.listAdmin(adminQuery()).total() == 1) {
            assertEquals(LocalDate.of(2026, 10, 1), movie.releaseDate());
            assertCode("MOVIE_SCREENING_CONFLICT", () -> movies.update("1", new UpdateMovieRequest(
                    "集成电影1", null, LocalDate.of(2026, 10, 2), 120, null, movie.version())));
        } else {
            assertEquals(LocalDate.of(2026, 10, 2), movie.releaseDate());
            assertEquals(0L, scalar("SELECT schedule_version FROM hall WHERE id = 1"));
        }
    }

    @Test
    void publicSqlFiltersHiddenResourcesAndAdminPagingUsesStableHalfOpenRanges() {
        seedMovie(2, 1);
        seedHall(2);
        jdbc.update("UPDATE hall SET status = 1 WHERE id = 2");
        seedScreening(101, 1, 1, "2026-10-01T12:00:00", 0);
        seedScreening(102, 1, 1, "2026-10-01T15:00:00", 1);
        seedScreening(103, 1, 1, "2026-10-01T18:00:00", 2);
        seedScreening(104, 1, 1, "2026-10-01T08:15:00", 1);
        seedScreening(105, 2, 1, "2026-10-01T21:00:00", 0);
        seedScreening(106, 1, 2, "2026-10-01T12:00:00", 0);
        seedScreening(107, 1, 1, "2026-10-02T12:00:00", 3);
        seedScreening(108, 1, 1, "2026-10-03T12:00:00", 4);

        var publicPage = commands.listPublic(new PublicScreeningQueryRequest("1", "1",
                LocalDate.of(2026, 10, 1), 1, 1));
        assertEquals(2, publicPage.total());
        assertEquals(List.of("101"), publicPage.items().stream().map(value -> value.id()).toList());
        assertFalse(publicPage.items().getFirst().canPurchase());
        var secondPage = commands.listPublic(new PublicScreeningQueryRequest("1", "1",
                LocalDate.of(2026, 10, 1), 2, 1));
        assertEquals(List.of("102"), secondPage.items().stream().map(value -> value.id()).toList());
        assertTrue(secondPage.items().getFirst().canPurchase());
        for (String id : List.of("103", "104", "105", "106", "107", "108")) {
            assertCode("RESOURCE_NOT_FOUND", () -> commands.getPublic(id));
            assertCode("RESOURCE_NOT_FOUND", () -> commands.getSeats(id));
        }
        assertEquals(8, commands.listAdmin(adminQuery()).total());
        var adminPage = commands.listAdmin(new AdminScreeningQueryRequest("1", "1", null, 1, 10, null,
                OffsetDateTime.parse("2026-10-01T04:00:00Z"), OffsetDateTime.parse("2026-10-01T10:00:00Z"),
                null, null, null, null, "startTime", "desc"));
        assertEquals(2, adminPage.total());
        assertEquals(List.of("102", "101"), adminPage.items().stream().map(value -> value.id()).toList());
        assertEquals(0, commands.listPublic(new PublicScreeningQueryRequest(null, null, null,
                Integer.MAX_VALUE, 100)).items().size());
    }

    @Test
    void seatsHttpQueryCommitsExpiryReleaseAndNeverLeaksAnotherOrdersData() throws Exception {
        ScreeningCreatedVO created = commands.create(immediate("1", "1", "2026-10-01T12:00:00+08:00"));
        seedOrder(900, created.id(), 0, NOW);
        seedOrder(901, created.id(), 0, NOW.plusSeconds(600));
        attachHeldSeat(900, created.id(), 11);
        attachHeldSeat(901, created.id(), 12);
        assertEquals(List.of(900L), context.getBean(OrderMapper.class)
                .selectDueOrders(Long.parseLong(created.id()), NOW, null, null, 100).stream()
                .map(order -> order.getId()).toList());
        assertTrue(AopUtils.isAopProxy(context.getBean(OrderExpirationProcessor.class)));

        api().perform(get("/screenings/" + created.id() + "/seats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canPurchase").value(true))
                .andExpect(jsonPath("$.data.seats[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.seats[1].status").value("HELD"))
                .andExpect(jsonPath("$.data.seats[1].orderId").doesNotExist())
                .andExpect(jsonPath("$.data.seats[1].userId").doesNotExist())
                .andExpect(jsonPath("$.data.seats[1].expiresAt").doesNotExist());
        assertEquals(3L, scalar("SELECT status FROM orders WHERE id = 900"));
        assertEquals(0L, scalar("SELECT status FROM orders WHERE id = 901"));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM screening_seat WHERE seat_id = 11 AND order_id IS NULL AND status = 0"));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM screening_seat WHERE seat_id = 12 AND order_id = 901 AND status = 1"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4})
    void manualOffSalePreservesOrdersAndActiveOrdersPreventClose(int orderStatus) {
        ScreeningCreatedVO created = commands.create(immediate("1", "1", "2026-10-01T12:00:00+08:00"));
        seedOrder(900, created.id(), orderStatus, NOW.plusSeconds(600));

        commands.offSale(created.id(), new ScreeningVersionRequest(0));

        assertEquals(orderStatus, scalar("SELECT status FROM orders WHERE id = 900"));
        assertEquals(LocalDateTime.parse("2026-10-01T00:10:00"), jdbc.queryForObject(
                "SELECT expires_at FROM orders WHERE id = 900", LocalDateTime.class));
        assertCode("RESOURCE_CONFLICT", () -> commands.close(created.id(), new ScreeningVersionRequest(1)));
        assertEquals("OFF_SALE", commands.getAdmin(created.id()).status());
        assertEquals(1, commands.getAdmin(created.id()).version());
        assertEquals(0L, scalar("SELECT schedule_version FROM hall WHERE id = 1"));
    }

    @Test
    void cancelledOrderHistoryPreventsLayoutRewriteButAllowsClosing() {
        ScreeningCreatedVO created = commands.create(scheduled("1", "1", "2026-10-01T12:00:00+08:00"));
        seedOrder(900, created.id(), 2, NOW.plusSeconds(600));
        var seats = commands.getSeats(created.id()).seats();

        assertCode("RESOURCE_CONFLICT", () -> commands.update(created.id(),
                update("1", "1", "2026-10-01T13:00:00+08:00", 0)));
        assertEquals(0, commands.getAdmin(created.id()).version());
        assertEquals(seats, commands.getSeats(created.id()).seats());
        assertEquals("CLOSED", commands.close(created.id(), new ScreeningVersionRequest(0)).status());
    }

    @Test
    void updatingMovieDurationPreservesSnapshotUntilWaitSaleScreeningIsExplicitlyEdited() {
        jdbc.update("UPDATE movie SET status = 1 WHERE id = 1");
        ScreeningCreatedVO created = commands.create(scheduled("1", "1", "2026-10-01T12:00:00+08:00"));

        movies.update("1", new UpdateMovieRequest("集成电影1", null, LocalDate.of(2026, 10, 1), 150, null, 1));

        assertEquals(120, commands.getAdmin(created.id()).durationMinutesSnapshot());
        assertEquals(OffsetDateTime.parse("2026-10-01T14:00:00+08:00"), commands.getAdmin(created.id()).endTime());
        var edited = commands.update(created.id(), update("1", "1", "2026-10-01T12:00:00+08:00", 0));
        assertEquals(150, edited.durationMinutesSnapshot());
        assertEquals(OffsetDateTime.parse("2026-10-01T14:30:00+08:00"), edited.endTime());
    }

    @Test
    void lifecycleAdvancesAtActualCutoffsWithoutReopeningManualOffSaleOrChangingTerminals() {
        for (int hall = 2; hall <= 5; hall++) {
            seedHall(hall);
            seedSeat(hall * 10L + 1, hall, 1, 0);
        }
        seedMovie(2, 1);
        ScreeningCreatedVO ended = commands.create(immediate("1", "1", "2026-10-01T09:00:00+08:00"));
        ScreeningCreatedVO automatic = commands.create(scheduled("1", "2", "2026-10-01T11:00:00+08:00"));
        ScreeningCreatedVO closed = commands.create(scheduled("1", "3", "2026-10-01T12:00:00+08:00"));
        commands.close(closed.id(), new ScreeningVersionRequest(0));
        ScreeningCreatedVO manual = commands.create(immediate("1", "4", "2026-10-01T13:00:00+08:00"));
        commands.offSale(manual.id(), new ScreeningVersionRequest(0));
        ScreeningCreatedVO hidden = commands.create(scheduled("2", "5", "2026-10-01T14:00:00+08:00"));
        var scheduler = lifecycleScheduler();

        clock.set(Instant.parse("2026-10-01T01:00:00Z"));
        scheduler.advanceDueScreenings();

        assertEquals("OFF_SALE", commands.getAdmin(ended.id()).status());
        assertEquals("ON_SALE", commands.getAdmin(automatic.id()).status());
        assertEquals("CLOSED", commands.getAdmin(closed.id()).status());
        assertEquals("OFF_SALE", commands.getAdmin(manual.id()).status());
        assertEquals("WAIT_SALE", commands.getAdmin(hidden.id()).status());

        clock.set(Instant.parse("2026-10-01T03:00:00Z"));
        assertCode("SCREENING_STATUS_CONFLICT", () -> commands.close(ended.id(),
                new ScreeningVersionRequest(commands.getAdmin(ended.id()).version())));
        assertCode("RESOURCE_NOT_FOUND", () -> commands.getPublic(automatic.id()));
        scheduler.advanceDueScreenings();
        assertEquals("ENDED", commands.getAdmin(ended.id()).status());
        assertEquals("OFF_SALE", commands.getAdmin(automatic.id()).status());
        assertEquals("CLOSED", commands.getAdmin(closed.id()).status());

        clock.set(Instant.parse("2026-10-01T05:45:00Z"));
        scheduler.advanceDueScreenings();
        assertEquals("ENDED", commands.getAdmin(automatic.id()).status());
        assertEquals("OFF_SALE", commands.getAdmin(hidden.id()).status());
        assertEquals("CLOSED", commands.getAdmin(closed.id()).status());
        int version = commands.getAdmin(ended.id()).version();
        commands.advanceLifecycle(Long.parseLong(ended.id()));
        assertEquals(version, commands.getAdmin(ended.id()).version());

        clock.set(Instant.parse("2026-10-01T08:00:00Z"));
        scheduler.advanceDueScreenings();
        assertEquals("CLOSED", commands.getAdmin(closed.id()).status());
        assertEquals("ENDED", commands.getAdmin(hidden.id()).status());
        assertEquals("ENDED", commands.getAdmin(manual.id()).status());
    }

    @Test
    void hallWithoutEnabledSeatsAndDisabledHallCannotCreateOrConsumeVersions() {
        seedHall(2);
        seedSeat(21, 2, 1, 1);
        assertCode("BUSINESS_RULE_VIOLATION", () ->
                commands.create(scheduled("1", "2", "2026-10-01T12:00:00+08:00")));
        jdbc.update("UPDATE hall SET status = 1 WHERE id = 1");
        assertCode("BUSINESS_RULE_VIOLATION", () ->
                commands.create(scheduled("1", "1", "2026-10-01T12:00:00+08:00")));

        assertEquals(0, commands.listAdmin(adminQuery()).total());
        assertEquals(0, movies.getAdmin("1").version());
        assertEquals(0L, scalar("SELECT SUM(schedule_version) FROM hall"));
        assertEquals(0L, scalar("SELECT COUNT(*) FROM screening_seat"));
    }

    @Test
    void endedScreeningStillReservesItsHistoricalTurnaroundUntilTheBoundary() {
        // Imported history may retain a different turnaround snapshot; ENDED must not erase it.
        seedScreening(100, 1, 1, "2026-10-01T06:00:00", 4);
        jdbc.update("UPDATE screening SET turnaround_minutes_snapshot = 30 WHERE id = 100");

        assertCode("RESOURCE_CONFLICT", () ->
                commands.create(immediate("1", "1", "2026-10-01T08:29:59+08:00")));
        assertEquals(0, movies.getAdmin("1").version());
        assertEquals(0L, scalar("SELECT schedule_version FROM hall WHERE id = 1"));

        ScreeningCreatedVO adjacent = commands.create(immediate("1", "1", "2026-10-01T08:30:00+08:00"));
        assertEquals("ON_SALE", commands.getAdmin(adjacent.id()).status());
        assertEquals("ENDED", commands.getAdmin("100").status());
    }

    @Test
    void maintenanceAllowsFutureReferencesButAppendStillRejectsClosedScreenings() {
        ScreeningCreatedVO created = commands.create(scheduled("1", "1", "2026-10-01T12:00:00+08:00"));
        var maintenance = context.getBean(io.github.chenforegone.encoreticket.service.HallService.class);
        var version = new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(0);
        assertEquals("DISABLED", maintenance.disable("1", version).status());
        maintenance.enable("1", new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(1));
        assertEquals("DISABLED", maintenance.disableSeat("1", "11", version).status());
        commands.close(created.id(), new ScreeningVersionRequest(0));
        assertCode("RESOURCE_CONFLICT", () -> maintenance.addSeats("1",
                new io.github.chenforegone.encoreticket.model.dto.CreateSeatsRequest(List.of(
                        new io.github.chenforegone.encoreticket.model.dto.SeatPositionRequest(3, 1)))));
        assertEquals("CLOSED", commands.getAdmin(created.id()).status());
        assertEquals(0L, scalar("SELECT schedule_version FROM hall WHERE id = 1"));
    }

    @Test
    void physicalSeatDisableHidesPublicSeatAndPreventsManualAndAutomaticOpening() {
        var maintenance = context.getBean(io.github.chenforegone.encoreticket.service.HallService.class);
        ScreeningCreatedVO created = commands.create(scheduled("1", "1", "2026-10-01T12:00:00+08:00"));
        maintenance.disableSeat("1", "11", new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(0));
        maintenance.disableSeat("1", "12", new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(0));
        assertTrue(commands.getSeats(created.id()).seats().isEmpty());
        assertCode("BUSINESS_RULE_VIOLATION", () -> commands.onSale(created.id(), new ScreeningVersionRequest(0)));
        clock.set(Instant.parse("2026-10-01T01:00:00Z"));
        lifecycleScheduler().advanceDueScreenings();
        assertEquals("WAIT_SALE", commands.getAdmin(created.id()).status());
        maintenance.enableSeat("1", "11", new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(1));
        lifecycleScheduler().advanceDueScreenings();
        assertEquals("ON_SALE", commands.getAdmin(created.id()).status());
        assertEquals(List.of("11"), commands.getSeats(created.id()).seats().stream().map(item -> item.seatId()).toList());
        assertEquals(2L, scalar("SELECT COUNT(*) FROM screening_seat WHERE screening_id=" + created.id()));
    }

    @Test
    void heldAndSoldBlockMaintenanceWithoutExpiringOrdersButEndedScreeningsDoNot() {
        var maintenance = context.getBean(io.github.chenforegone.encoreticket.service.HallService.class);
        ScreeningCreatedVO created = commands.create(scheduled("1", "1", "2026-10-01T12:00:00+08:00"));
        jdbc.update("UPDATE screening_seat SET status=1,order_id=999 WHERE screening_id=? AND seat_id=11", created.id());
        var version = new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(0);
        assertFalse(maintenance.layout("1").seats().getFirst().canDisable());
        assertCode("RESOURCE_CONFLICT", () -> maintenance.disableSeat("1", "11", version));
        jdbc.update("UPDATE screening_seat SET status=2 WHERE screening_id=? AND seat_id=11", created.id());
        assertCode("RESOURCE_CONFLICT", () -> maintenance.disableSeat("1", "11", version));
        clock.set(Instant.parse("2026-10-01T06:00:00Z"));
        assertEquals("DISABLED", maintenance.disableSeat("1", "11", version).status());
        assertEquals(2L, scalar("SELECT status FROM screening_seat WHERE screening_id=" + created.id() + " AND seat_id=11"));
        assertEquals(999L, scalar("SELECT order_id FROM screening_seat WHERE screening_id=" + created.id() + " AND seat_id=11"));
    }

    @Test
    void atomicHallCreationRollsBackWhenASeatInsertFails() {
        var maintenance = context.getBean(io.github.chenforegone.encoreticket.service.HallService.class);
        jdbc.execute("CREATE TRIGGER hall_test_fail_seat BEFORE INSERT ON seat FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test insert failure'");
        try {
            assertThrows(DataAccessException.class, () -> maintenance.create(new io.github.chenforegone.encoreticket.model.dto.CreateHallRequest(
                    "NEW", "新厅", List.of(new io.github.chenforegone.encoreticket.model.dto.SeatPositionRequest(1, 1)))));
            assertEquals(0, maintenance.list(new io.github.chenforegone.encoreticket.model.dto.HallQueryRequest("NEW", null, null, null, null, null, null)).total());
        } finally {
            jdbc.execute("DROP TRIGGER hall_test_fail_seat");
        }
    }

    @Test
    void hallMaintenanceWaitsForTheSamePrimaryKeyLockAsScheduling() throws Exception {
        var maintenance = context.getBean(io.github.chenforegone.encoreticket.service.HallService.class);
        var transaction = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempting = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> transaction.executeWithoutResult(ignored -> {
                context.getBean(HallScheduleGuard.class).lockHallRows(1L);
                held.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            var writer = executor.submit(() -> {
                attempting.countDown();
                return maintenance.disable("1", new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(0));
            });
            assertTrue(attempting.await(10, TimeUnit.SECONDS));
            try {
                assertThrows(java.util.concurrent.TimeoutException.class, () -> writer.get(300, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            holder.get(10, TimeUnit.SECONDS);
            assertEquals("DISABLED", writer.get(10, TimeUnit.SECONDS).status());
            assertCode("BUSINESS_RULE_VIOLATION", () -> commands.create(scheduled("1", "1", "2026-10-01T12:00:00+08:00")));
        } finally { release.countDown(); }
    }

    @Test
    void simultaneousSeatAppendAndFirstScreeningLeaveOneConsistentLayout() throws Exception {
        var maintenance = context.getBean(io.github.chenforegone.encoreticket.service.HallService.class);
        var outcomes = race(
                () -> maintenance.addSeats("1", new io.github.chenforegone.encoreticket.model.dto.CreateSeatsRequest(List.of(
                        new io.github.chenforegone.encoreticket.model.dto.SeatPositionRequest(4, 1)))),
                () -> commands.create(scheduled("1", "1", "2026-10-01T12:00:00+08:00")));
        var append = outcomes.getFirst();
        var screening = outcomes.getLast();
        assertNull(screening.failure(), () -> "排片不应失败：" + screening.failure());
        assertInstanceOf(ScreeningCreatedVO.class, screening.result());
        if (append.failure() == null) {
            var added = assertInstanceOf(io.github.chenforegone.encoreticket.model.vo.SeatsCreatedVO.class,
                    append.result());
            assertEquals(1, added.seats().size());
            assertEquals(4, added.seats().getFirst().rowNumber());
            assertEquals(1, added.seats().getFirst().seatNumber());
        } else {
            var failure = assertInstanceOf(ApplicationException.class, append.failure());
            assertEquals("RESOURCE_CONFLICT", failure.getErrorCode().name());
        }
        assertEquals(1, commands.listAdmin(adminQuery()).total());
        var only = commands.listAdmin(adminQuery()).items().getFirst();
        assertEquals(((ScreeningCreatedVO) screening.result()).id(), only.id());
        long enabledCount = maintenance.get("1").enabledSeatCount();
        assertEquals(append.failure() == null ? 3L : 2L, enabledCount);
        assertEquals(append.failure() == null ? 4L : 3L, maintenance.get("1").seatCount());
        assertEquals(enabledCount, commands.getSeats(only.id()).seats().size());
        assertEquals(enabledCount, scalar("SELECT COUNT(*) FROM screening_seat WHERE screening_id=" + only.id()));
        assertEquals(append.failure() == null ? 1L : 0L,
                scalar("SELECT COUNT(*) FROM seat WHERE hall_id=1 AND `row_number`=4 AND seat_number=1"));
        assertCode("RESOURCE_CONFLICT", () -> maintenance.addSeats("1",
                new io.github.chenforegone.encoreticket.model.dto.CreateSeatsRequest(List.of(
                        new io.github.chenforegone.encoreticket.model.dto.SeatPositionRequest(5, 1)))));
    }

    @Test
    void newHallAndSeatVersionsDefaultToZeroAndRejectNegativeValues() {
        var maintenance = context.getBean(io.github.chenforegone.encoreticket.service.HallService.class);
        var created = maintenance.create(new io.github.chenforegone.encoreticket.model.dto.CreateHallRequest("NEW", "新厅",
                List.of(new io.github.chenforegone.encoreticket.model.dto.SeatPositionRequest(1, 3))));
        assertEquals(0, created.version());
        assertEquals(0, maintenance.layout(created.hallId()).seats().getFirst().version());
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE hall SET version=-1 WHERE id=?", created.hallId()));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE seat SET version=-1 WHERE hall_id=?", created.hallId()));
    }

    @Test
    void oneHundredOfflineMoviesDoNotStarveALaterEligibleAutomaticOpening() {
        seedMovie(2, 1);
        for (int index = 0; index < 100; index++) {
            seedScreening(1000L + index, 2, 1,
                    LocalDateTime.of(2026, 10, 2, 12, 0).plusHours(index * 3L).toString(), 0);
        }
        seedScreening(1100, 1, 1, "2026-11-01T12:00:00", 0);
        seedAvailableScreeningSeat(5000, 1100, 11);

        lifecycleScheduler().advanceDueScreenings();

        assertEquals("ON_SALE", commands.getAdmin("1100").status());
        assertEquals(100L, scalar("SELECT COUNT(*) FROM screening WHERE movie_id = 2 AND status = 0"));
        assertEquals(101, commands.listAdmin(adminQuery()).total());
    }

    private ScreeningLifecycleScheduler lifecycleScheduler() {
        return new ScreeningLifecycleScheduler(context.getBean(ScreeningLifecycleMapper.class), commands, clock, 100);
    }

    private void seedScreening(long id, long movieId, long hallId, String start, int status) {
        LocalDateTime time = LocalDateTime.parse(start);
        jdbc.update("""
                INSERT INTO screening (id,movie_id,hall_id,price,start_time,end_time,duration_minutes_snapshot,
                    turnaround_minutes_snapshot,admission_open_time,sale_start_time,sale_end_time,status,version,create_time,update_time)
                VALUES (?,?,?,35.00,?,?,120,15,?,'2026-09-30 12:00:00',?,?,0,'2026-09-01','2026-09-01')
                """, id, movieId, hallId, time, time.plusMinutes(120), time.minusMinutes(15), time.minusMinutes(15), status);
    }

    private void seedAvailableScreeningSeat(long id, long screeningId, long physicalSeat) {
        jdbc.update("""
                INSERT INTO screening_seat(id,screening_id,seat_id,status,create_time,update_time)
                VALUES (?,?,?,0,'2026-09-01','2026-09-01')
                """, id, screeningId, physicalSeat);
    }

    private void seedOrder(long orderId, String screeningId, int status, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO orders(id,order_no,user_id,screening_id,total_amount,expires_at,status,create_time,update_time)
                VALUES (?,?,700,?,35.00,?,?,'2026-09-30 23:55:00','2026-09-30 23:55:00')
                """, orderId, "TEST" + orderId, Long.parseLong(screeningId),
                LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC), status);
    }

    private void attachHeldSeat(long orderId, String screeningId, long physicalSeat) {
        Long seatId = jdbc.queryForObject("SELECT id FROM screening_seat WHERE screening_id = ? AND seat_id = ?",
                Long.class, Long.parseLong(screeningId), physicalSeat);
        jdbc.update("UPDATE screening_seat SET status = 1, order_id = ? WHERE id = ?", orderId, seatId);
        jdbc.update("""
                INSERT INTO order_item(id,order_id,screening_seat_id,unit_price,create_time,update_time)
                VALUES (?,?,?,35.00,'2026-09-30 23:55:00','2026-09-30 23:55:00')
                """, orderId + 10000, orderId, seatId);
    }

    private MockMvc api() {
        // These requests exercise routing/validation/persistence; security has its own filter-chain tests.
        return MockMvcBuilders.standaloneSetup(
                new io.github.chenforegone.encoreticket.controller.admin.AdminScreeningController(commands),
                new io.github.chenforegone.encoreticket.controller.user.UserScreeningController(commands))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private void assertCode(String code, Runnable operation) {
        assertEquals(code, assertThrows(ApplicationException.class, operation::run).getErrorCode().name());
    }

    private long scalar(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private PublicScreeningQueryRequest publicQuery() {
        return new PublicScreeningQueryRequest(null, null, null, null, null);
    }

    private AdminScreeningQueryRequest adminQuery() {
        return new AdminScreeningQueryRequest(null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private UpdateScreeningRequest update(String movie, String hall, String start, int version) {
        return new UpdateScreeningRequest(movie, hall, "35.00", OffsetDateTime.parse(start),
                OffsetDateTime.parse("2026-10-01T09:00:00+08:00"), null, version);
    }

    private List<Outcome> race(Callable<Object> first, Callable<Object> second) throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = workers.submit(() -> runContender(first, ready, go));
            var right = workers.submit(() -> runContender(second, ready, go));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both independent requests must be ready");
            go.countDown();
            try {
                return List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS));
            } finally {
                left.cancel(true);
                right.cancel(true);
            }
        }
    }

    private Outcome runContender(Callable<Object> operation, CountDownLatch ready, CountDownLatch go)
            throws Exception {
        ready.countDown();
        if (!go.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent test did not start both requests");
        }
        try {
            return new Outcome(operation.call(), null);
        } catch (RuntimeException exception) {
            return new Outcome(null, exception);
        }
    }

    private void assertOneCommittedOneBusinessConflict(List<Outcome> outcomes) {
        assertEquals(1, outcomes.stream().filter(value -> value.failure() == null).count());
        var failure = assertInstanceOf(ApplicationException.class, outcomes.stream()
                .filter(value -> value.failure() != null).findFirst().orElseThrow().failure());
        assertTrue(List.of("RESOURCE_CONFLICT", "HALL_SCHEDULE_VERSION_CONFLICT", "MOVIE_VERSION_CONFLICT")
                .contains(failure.getErrorCode().name()), failure.getErrorCode().name());
    }

    /**
     * Captures concurrent request outcomes without hiding unexpected infrastructure failures.
     *
     * @author c1342
     */
    private record Outcome(Object result, RuntimeException failure) {
    }

    private CreateScreeningRequest scheduled(String movieId, String hallId, String start) {
        return new CreateScreeningRequest(movieId, hallId, "35.00", OffsetDateTime.parse(start),
                OffsetDateTime.parse("2026-10-01T09:00:00+08:00"), null, ScreeningSaleMode.SCHEDULED);
    }

    private CreateScreeningRequest immediate(String movieId, String hallId, String start) {
        return new CreateScreeningRequest(movieId, hallId, "35.00", OffsetDateTime.parse(start),
                null, null, ScreeningSaleMode.IMMEDIATE);
    }

    private void seedMovie(long id, int status) {
        jdbc.update("""
                INSERT INTO movie (id,title,release_date,duration_minutes,status,version,create_time,update_time)
                VALUES (?,?,'2026-10-01',120,?,0,'2026-09-01','2026-09-01')
                """, id, "集成电影" + id, status);
    }

    private void seedHall(long id) {
        jdbc.update("""
                INSERT INTO hall (id,hall_name,hall_no,status,schedule_version,create_time,update_time)
                VALUES (?,?,?,0,0,'2026-09-01','2026-09-01')
                """, id, "影厅" + id, "H" + id);
    }

    private void seedSeat(long id, long hallId, int number, int status) {
        jdbc.update("""
                INSERT INTO seat (id,hall_id,`row_number`,seat_number,status,create_time,update_time)
                VALUES (?,?,1,?,?,'2026-09-01','2026-09-01')
                """, id, hallId, number, status);
    }

    private void buildTransactionalContext() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("screening-transaction-test",
                new SpringManagedTransactionFactory(), data));
        GlobalConfigUtils.getGlobalConfig(configuration).setMetaObjectHandler(new MybatisPlusMetaObjectHandler());
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        configuration.addInterceptor(interceptor);
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml")) {
            try (InputStream input = resource.getInputStream()) {
                new XMLMapperBuilder(input, configuration, resource.toString(), configuration.getSqlFragments()).parse();
            }
        }
        for (Class<?> mapper : List.of(MovieMapper.class, HallMapper.class, ScreeningMapper.class,
                SeatMapper.class, ScreeningSeatMapper.class, ScreeningQueryMapper.class, OrderMapper.class,
                ScreeningLifecycleMapper.class)) {
            if (!configuration.hasMapper(mapper)) {
                configuration.addMapper(mapper);
            }
        }
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory);
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(data));
        context.registerBean(Clock.class, () -> clock);
        context.registerBean(MovieMapper.class, () -> session.getMapper(MovieMapper.class));
        context.registerBean(HallMapper.class, () -> session.getMapper(HallMapper.class));
        context.registerBean(ScreeningMapper.class, () -> session.getMapper(ScreeningMapper.class));
        context.registerBean(ScreeningQueryMapper.class, () -> session.getMapper(ScreeningQueryMapper.class));
        context.registerBean(SeatMapper.class, () -> session.getMapper(SeatMapper.class));
        context.registerBean(ScreeningSeatMapper.class, () -> session.getMapper(ScreeningSeatMapper.class));
        context.registerBean(OrderMapper.class, () -> session.getMapper(OrderMapper.class));
        context.registerBean(ScreeningLifecycleMapper.class, () -> session.getMapper(ScreeningLifecycleMapper.class));
        context.register(MovieConverter.class, ScreeningConverter.class, HallScheduleGuard.class,
                OrderExpirationProcessor.class, OrderExpirationService.class,
                MovieServiceImpl.class, ScreeningServiceImpl.class,
                io.github.chenforegone.encoreticket.service.impl.HallServiceImpl.class);
        context.refresh();
        commands = context.getBean(ScreeningService.class);
        movies = context.getBean(MovieService.class);
    }

    /**
     * Enables production service annotations without starting application infrastructure.
     *
     * @author c1342
     */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {
    }

    /**
     * Advances business time deterministically while services keep using their injected Clock.
     *
     * @author c1342
     */
    private static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> now;

        private AdjustableClock(Instant initial) {
            now = new AtomicReference<>(initial);
        }

        void set(Instant value) {
            now.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
