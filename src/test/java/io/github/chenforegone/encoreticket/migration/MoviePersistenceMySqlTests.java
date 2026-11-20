package io.github.chenforegone.encoreticket.migration;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.chenforegone.encoreticket.common.exception.GlobalExceptionHandler;
import io.github.chenforegone.encoreticket.config.MybatisPlusMetaObjectHandler;
import io.github.chenforegone.encoreticket.converter.MovieConverter;
import io.github.chenforegone.encoreticket.mapper.MovieMapper;
import io.github.chenforegone.encoreticket.model.entity.Movie;
import io.github.chenforegone.encoreticket.model.enums.MovieStatus;
import io.github.chenforegone.encoreticket.service.impl.MovieServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.net.URI;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Movie Mapper and migration contracts against an explicitly opted-in disposable MySQL server.
 *
 * <p>Uses {@code flyway.poc.enabled}, {@code flyway.poc.url}, {@code flyway.poc.username}
 * and the {@code FLYWAY_POC_PASSWORD} environment variable (fallback {@code MYSQL_PASSWORD}).
 * The supplied URL must name an existing {@code encore_ticket_flyway_poc_*} schema;
 * the account needs permission to create and drop this test's isolated schemas.
 * No application context or publication scheduler is started.
 *
 * @author c1342
 */
@EnabledIfSystemProperty(named = "flyway.poc.enabled", matches = "true")
class MoviePersistenceMySqlTests {

    private DriverManagerDataSource controlDataSource;
    private DriverManagerDataSource dataSource;
    private String schema;
    private boolean schemaCreated;

    @Test
    void adminCanPublishBeforeScheduledTimeAndPublicCanImmediatelyReadMovie() throws Exception {
        migrations().migrate();
        try (SqlSession session = mapperFactory().openSession(true)) {
            MockMvc api = movieApi(session);
            String location = api.perform(post("/admin/movies").contentType(APPLICATION_JSON).content("""
                            {
                              "title": "提前手动发布",
                              "releaseDate": "2026-10-01",
                              "durationMinutes": 120,
                              "publishAt": "2099-10-01T10:00:00+08:00"
                            }
                            """))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getHeader("Location");
            api.perform(post(location + "/publish").contentType(APPLICATION_JSON).content("{\"version\":0}"))
                    .andExpect(status().isNoContent());
            api.perform(get(location))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ONLINE"))
                    .andExpect(jsonPath("$.data.version").value(1))
                    .andExpect(jsonPath("$.data.publishAt").isEmpty());
            api.perform(get(location.substring("/admin".length())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("提前手动发布"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void repeatedPublicationReturnsAlreadyReleasedWithoutIncrementingVersion(int version) throws Exception {
        migrations().migrate();
        insertMovie(1, "重复发布", MovieStatus.OFFLINE, "2026-10-01", 0);
        try (SqlSession session = mapperFactory().openSession(true)) {
            MockMvc api = movieApi(session);
            api.perform(post("/admin/movies/1/publish").contentType(APPLICATION_JSON).content("{\"version\":0}"))
                    .andExpect(status().isNoContent());
            api.perform(post("/admin/movies/1/publish").contentType(APPLICATION_JSON)
                            .content("{\"version\":" + version + "}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MOVIE_ALREADY_RELEASED"))
                    .andExpect(jsonPath("$.message").value("电影已经上架"));
            api.perform(get("/admin/movies/1"))
                    .andExpect(jsonPath("$.data.status").value("ONLINE"))
                    .andExpect(jsonPath("$.data.version").value(1));
        }
    }

    @Test
    void manualPublicationWinningPreventsAutomaticRepublishingAndOfflineStaysHidden() throws Exception {
        migrations().migrate();
        insertMovie(1, "手动抢先发布", MovieStatus.OFFLINE, "2026-10-01", 0);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE movie SET publish_at = '2026-01-01 00:00:00' WHERE id = 1");
        }
        SqlSessionFactory factory = mapperFactory();
        try (SqlSession manual = factory.openSession(true); SqlSession automatic = factory.openSession(true)) {
            MockMvc api = movieApi(manual);
            api.perform(post("/admin/movies/1/publish").contentType(APPLICATION_JSON).content("{\"version\":0}"))
                    .andExpect(status().isNoContent());
            var scheduler = new io.github.chenforegone.encoreticket.scheduler.MoviePublicationScheduler(
                    automatic.getMapper(MovieMapper.class));
            scheduler.publishDueMovies();
            api.perform(get("/admin/movies/1"))
                    .andExpect(jsonPath("$.data.status").value("ONLINE"))
                    .andExpect(jsonPath("$.data.version").value(1))
                    .andExpect(jsonPath("$.data.publishAt").isEmpty());
            api.perform(post("/admin/movies/1/offline").contentType(APPLICATION_JSON).content("{\"version\":1}"))
                    .andExpect(status().isNoContent());
            scheduler.publishDueMovies();
            api.perform(get("/admin/movies/1"))
                    .andExpect(jsonPath("$.data.status").value("OFFLINE"))
                    .andExpect(jsonPath("$.data.version").value(2));
            api.perform(get("/movies/1")).andExpect(status().isNotFound());
        }
    }

    @Test
    void automaticPublicationWinningAfterManualReadReturnsVersionConflict() throws Exception {
        migrations().migrate();
        insertMovie(1, "自动抢先发布", MovieStatus.OFFLINE, "2026-10-01", 0);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE movie SET publish_at = '2026-01-01 00:00:00' WHERE id = 1");
        }
        SqlSessionFactory factory = mapperFactory();
        factory.getConfiguration().addInterceptor(new BeforeManualWrite(() -> {
            try (SqlSession automatic = factory.openSession(true)) {
                new io.github.chenforegone.encoreticket.scheduler.MoviePublicationScheduler(
                        automatic.getMapper(MovieMapper.class)).publishDueMovies();
            }
        }));
        try (SqlSession manual = factory.openSession(true)) {
            MockMvc api = movieApi(manual);
            api.perform(post("/admin/movies/1/publish").contentType(APPLICATION_JSON).content("{\"version\":0}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MOVIE_VERSION_CONFLICT"));
            api.perform(get("/admin/movies/1"))
                    .andExpect(jsonPath("$.data.status").value("ONLINE"))
                    .andExpect(jsonPath("$.data.version").value(1))
                    .andExpect(jsonPath("$.data.publishAt").isEmpty());
        }
    }

    /**
     * 在真实数据库写入边界安排自动发布先完成，确定性重现读取与写入间的竞争。
     *
     * @author c1342
     */
    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(
            type = org.apache.ibatis.executor.Executor.class, method = "update",
            args = {org.apache.ibatis.mapping.MappedStatement.class, Object.class}))
    private static final class BeforeManualWrite implements org.apache.ibatis.plugin.Interceptor {
        private final Runnable automaticPublication;
        private boolean triggered;

        private BeforeManualWrite(Runnable automaticPublication) {
            this.automaticPublication = automaticPublication;
        }

        @Override
        public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            var statement = (org.apache.ibatis.mapping.MappedStatement) invocation.getArgs()[0];
            if (!triggered && statement.getId().equals(MovieMapper.class.getName() + ".updateById")) {
                triggered = true;
                automaticPublication.run();
            }
            return invocation.proceed();
        }
    }

    @Test
    void staleManualPublicationCannotChangeOfflineMovie() throws Exception {
        migrations().migrate();
        insertMovie(1, "版本冲突", MovieStatus.OFFLINE, "2026-10-01", 2);
        try (SqlSession session = mapperFactory().openSession(true)) {
            MockMvc api = movieApi(session);
            api.perform(post("/admin/movies/1/publish").contentType(APPLICATION_JSON).content("{\"version\":1}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MOVIE_VERSION_CONFLICT"));
            api.perform(get("/admin/movies/1"))
                    .andExpect(jsonPath("$.data.status").value("OFFLINE"))
                    .andExpect(jsonPath("$.data.version").value(2));
        }
    }

    @Test
    void offlineMovieDurationCanBeEditedWithoutPublicationTimeAndRejectsStaleVersion() throws Exception {
        migrations().migrate();
        insertMovie(1, "待补录片长", MovieStatus.OFFLINE, "2026-10-01", 0);
        try (SqlSession session = mapperFactory().openSession(true)) {
            MockMvc api = movieApi(session);
            String body = """
                    {
                      "title": "待补录片长",
                      "releaseDate": "2026-10-01",
                      "durationMinutes": 99,
                      "publishAt": null,
                      "version": 0
                    }
                    """;
            api.perform(put("/admin/movies/1").contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.durationMinutes").value(99))
                    .andExpect(jsonPath("$.data.version").value(1))
                    .andExpect(jsonPath("$.data.publishAt").isEmpty());
            api.perform(put("/admin/movies/1").contentType(APPLICATION_JSON)
                            .content(body.replace("99", "88")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MOVIE_VERSION_CONFLICT"));
            api.perform(get("/admin/movies/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.durationMinutes").value(99))
                    .andExpect(jsonPath("$.data.version").value(1));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 137, Integer.MAX_VALUE})
    void createdMovieRetainsDurationAcrossAdminAndPublicQueries(int durationMinutes) throws Exception {
        migrations().migrate();
        try (SqlSession session = mapperFactory().openSession(true)) {
            MockMvc api = movieApi(session);
            String location = api.perform(post("/admin/movies")
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {
                                      "title": "片长往返验证",
                                      "releaseDate": "2026-10-01",
                                      "durationMinutes": %d,
                                      "publishAt": "2099-10-01T10:00:00+08:00"
                                    }
                                    """.formatted(durationMinutes)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getHeader("Location");

            api.perform(get(location))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.durationMinutes").value(durationMinutes));
            api.perform(get("/admin/movies"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].durationMinutes").value(durationMinutes));

            session.getMapper(MovieMapper.class).publishDueMovies(Instant.parse("2100-01-01T00:00:00Z"), 10);
            api.perform(get(location.substring("/admin".length())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.durationMinutes").value(durationMinutes));
            api.perform(get("/movies"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].durationMinutes").value(durationMinutes));
        }
    }

    @Test
    void legacyMovieWithUnknownDurationRemainsQueryableWithoutInventingADefault() throws Exception {
        migrations("25").migrate();
        insertLegacyMovie(1, "待补录历史片长", MovieStatus.ONLINE, "2026-10-01", 0);
        try (SqlSession session = mapperFactory().openSession(true)) {
            MockMvc api = movieApi(session);
            for (String path : List.of("/admin/movies/1", "/movies/1")) {
                api.perform(get(path))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.durationMinutes").hasJsonPath())
                        .andExpect(jsonPath("$.data.durationMinutes").isEmpty());
            }
            for (String path : List.of("/admin/movies", "/movies")) {
                api.perform(get(path))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.items[0].durationMinutes").hasJsonPath())
                        .andExpect(jsonPath("$.data.items[0].durationMinutes").isEmpty());
            }
        }
    }

    @BeforeEach
    void createIsolatedSchema() throws SQLException {
        String url = System.getProperty("flyway.poc.url", "");
        if (!url.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("flyway.poc.url must be a MySQL JDBC URL");
        }
        URI uri = URI.create(url.substring("jdbc:".length()));
        if (uri.getUserInfo() != null || uri.getPath() == null
                || !uri.getPath().matches("/encore_ticket_flyway_poc_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Only explicitly disposable Flyway POC schemas are accepted");
        }
        String password = System.getenv("FLYWAY_POC_PASSWORD");
        if (password == null || password.isBlank()) {
            password = System.getenv("MYSQL_PASSWORD");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Set FLYWAY_POC_PASSWORD or MYSQL_PASSWORD");
        }
        String username = System.getProperty("flyway.poc.username", "encore_ticket");
        controlDataSource = new DriverManagerDataSource(url, username, password);
        schema = "encore_ticket_flyway_poc_movie_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = controlDataSource.getConnection(); Statement statement = connection.createStatement()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertEquals("MySQL", metadata.getDatabaseProductName());
            assertEquals(8, metadata.getDatabaseMajorVersion());
            assertEquals(4, metadata.getDatabaseMinorVersion());
            statement.executeUpdate("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
            schemaCreated = true;
        }
        String schemaUrl = "jdbc:mysql://" + uri.getRawAuthority() + "/" + schema
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        dataSource = new DriverManagerDataSource(schemaUrl, username, password);
    }

    @AfterEach
    void dropOnlyThisTestsSchema() throws SQLException {
        if (schemaCreated && schema.matches("encore_ticket_flyway_poc_movie_[a-f0-9]{32}")) {
            try (Connection connection = controlDataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE `" + schema + "`");
            }
        }
    }

    @Test
    void percentInTitleIsALiteralPrefixCharacter() throws Exception {
        migrations().migrate();
        insertMovie(1, "100%精彩", MovieStatus.ONLINE, "2026-10-01", 0);
        insertMovie(2, "1000精彩", MovieStatus.ONLINE, "2026-10-01", 0);

        IPage<Movie> result = query("100%", MovieStatus.ONLINE, null, null, 1, 10);

        assertEquals(List.of("100%精彩"), result.getRecords().stream().map(Movie::getTitle).toList());
        assertEquals(1, result.getTotal());
    }

    @ParameterizedTest(name = "literal prefix: {0}")
    @MethodSource("literalTitlePrefixes")
    void titlePrefixesPreserveLiteralCharacters(String prefix, String matchingTitle, String otherTitle)
            throws Exception {
        migrations().migrate();
        insertMovie(1, matchingTitle, MovieStatus.ONLINE, "2026-10-01", 0);
        insertMovie(2, otherTitle, MovieStatus.ONLINE, "2026-10-01", 0);

        IPage<Movie> result = query(prefix, MovieStatus.ONLINE, null, null, 1, 10);

        assertEquals(List.of(matchingTitle), result.getRecords().stream().map(Movie::getTitle).toList());
        assertEquals(1, result.getTotal());
    }

    private static Stream<Arguments> literalTitlePrefixes() {
        return Stream.of(
                Arguments.of("流浪", "流浪地球", "地球流浪"),
                Arguments.of("A_B", "A_B计划", "ACB计划"),
                Arguments.of("惊喜!", "惊喜!续集", "惊喜续集"),
                Arguments.of("路径\\", "路径\\续集", "路径续集"),
                Arguments.of("!%_\\", "!%_\\电影", "!%X\\电影"),
                Arguments.of("O'Brien", "O'Brien归来", "OBrien归来"));
    }

    @Test
    void literalPrefixKeepsStatusDateRangeStableSortingAndPagination() throws Exception {
        migrations().migrate();
        insertMovie(10, "100%首映", MovieStatus.ONLINE, "2026-10-01", 0);
        insertMovie(20, "100%隐藏", MovieStatus.OFFLINE, "2026-10-02", 0);
        insertMovie(30, "100%续集", MovieStatus.ONLINE, "2026-10-03", 0);
        insertMovie(31, "100%特别篇", MovieStatus.ONLINE, "2026-10-03", 0);
        insertMovie(40, "100%旧片", MovieStatus.ONLINE, "2026-09-01", 0);
        insertMovie(50, "1000其他", MovieStatus.ONLINE, "2026-10-02", 0);
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 3);

        IPage<Movie> publicPage = query("100%", MovieStatus.ONLINE, from, to, 2, 1);
        IPage<Movie> adminPage = query("100%", null, from, to, 2, 2);

        assertEquals(3, publicPage.getTotal());
        assertEquals(List.of(30L), publicPage.getRecords().stream().map(Movie::getId).toList());
        assertEquals(4, adminPage.getTotal());
        assertEquals(List.of(30L, 31L), adminPage.getRecords().stream().map(Movie::getId).toList());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void absentTitleDoesNotAddAPrefixFilter(String title) throws Exception {
        migrations().migrate();
        insertMovie(1, "流浪地球", MovieStatus.ONLINE, "2026-10-01", 0);
        insertMovie(2, "100%精彩", MovieStatus.ONLINE, "2026-10-02", 0);
        insertMovie(3, "隐藏电影", MovieStatus.OFFLINE, "2026-10-03", 0);

        IPage<Movie> result = query(title, MovieStatus.ONLINE, null, null, 1, 10);

        assertEquals(List.of(1L, 2L), result.getRecords().stream().map(Movie::getId).toList());
        assertEquals(2, result.getTotal());
    }

    @Test
    void rejectsInsertingANegativeMovieVersion() {
        migrations().migrate();

        SQLException exception = assertThrows(SQLException.class,
                () -> insertMovie(1, "Invalid version", MovieStatus.OFFLINE, "2026-10-01", -1));

        assertEquals(3819, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("ck_movie_version"));
    }

    @Test
    void rejectsUpdatingAMovieVersionBelowZeroWithoutChangingTheStoredValue() throws SQLException {
        migrations().migrate();
        insertMovie(1, "Existing movie", MovieStatus.OFFLINE, "2026-10-01", 7);

        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("UPDATE movie SET version = -1 WHERE id = ?")) {
            statement.setLong(1, 1);
            SQLException exception = assertThrows(SQLException.class, statement::executeUpdate);
            assertEquals(3819, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("ck_movie_version"));
        }
        assertEquals(7, movieVersion(1));
    }

    @Test
    void emptySchemaReplaysV1ThroughV4AndRepeatedMigrationDoesNothing() throws SQLException {
        Flyway flyway = migrations("24");

        assertEquals(8, flyway.migrate().migrationsExecuted);
        assertEquals("24", flyway.info().current().getVersion().getVersion());
        insertLegacyMovie(1, "Version zero", MovieStatus.OFFLINE, "2026-10-01", 0);
        insertLegacyMovie(2, "Positive version", MovieStatus.ONLINE, "2026-10-02", 7);
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(0, movieVersion(1));
        assertEquals(7, movieVersion(2));
    }

    @Test
    void upgradingV3AddsTheConstraintAndPreservesExistingMovieData() throws SQLException {
        assertEquals(7, migrations("23").migrate().migrationsExecuted);
        insertLegacyMovie(1, "已有电影", MovieStatus.ONLINE, "2026-10-01", 7);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE movie SET description = '保留简介', publish_at = '2026-10-01 00:00:00' WHERE id = 1");
        }

        Flyway flyway = migrations("24");
        assertEquals(1, flyway.migrate().migrationsExecuted);
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT * FROM movie WHERE id = 1")) {
            assertTrue(result.next());
            assertEquals("已有电影", result.getString("title"));
            assertEquals("保留简介", result.getString("description"));
            assertEquals(LocalDate.of(2026, 10, 1), result.getObject("release_date", LocalDate.class));
            assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), result.getObject("publish_at", LocalDateTime.class));
            assertEquals(MovieStatus.ONLINE.getValue(), result.getInt("status"));
            assertEquals(7, result.getInt("version"));
            assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), result.getObject("create_time", LocalDateTime.class));
            assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), result.getObject("update_time", LocalDateTime.class));
        }
        SQLException exception = assertThrows(SQLException.class,
                () -> insertLegacyMovie(2, "Invalid version", MovieStatus.OFFLINE, "2026-10-01", -1));
        assertEquals(3819, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("ck_movie_version"));
    }

    @Test
    void upgradingV3WithANegativeVersionFailsWithoutResettingExistingData() throws SQLException {
        migrations("23").migrate();
        insertLegacyMovie(1, "需调查的历史记录", MovieStatus.OFFLINE, "2026-10-01", -3);

        FlywayException exception = assertThrows(FlywayException.class, () -> migrations("24").migrate());

        assertTrue(exception.getMessage().contains("ck_movie_version"));
        assertEquals(-3, movieVersion(1));
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '24' AND success = 1")) {
            assertTrue(result.next());
            assertEquals(0, result.getLong(1));
        }
    }

    private Flyway migrations() {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .createSchemas(false).cleanDisabled(true).load();
    }

    private Flyway migrations(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(target).createSchemas(false).cleanDisabled(true).load();
    }

    private int movieVersion(long id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT version FROM movie WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private void insertMovie(long id, String title, MovieStatus status, String releaseDate, int version)
            throws SQLException {
        insertMovie(id, title, status, releaseDate, version, true);
    }

    private void insertLegacyMovie(long id, String title, MovieStatus status, String releaseDate, int version)
            throws SQLException {
        insertMovie(id, title, status, releaseDate, version, false);
    }

    private void insertMovie(long id, String title, MovieStatus status, String releaseDate, int version,
            boolean withDuration) throws SQLException {
        String sql = "INSERT INTO movie (id, title, release_date, status, version, create_time, update_time"
                + (withDuration ? ", duration_minutes" : "") + ") "
                + "VALUES (?, ?, ?, ?, ?, '2026-09-01 00:00:00', '2026-09-01 00:00:00'"
                + (withDuration ? ", ?" : "") + ")";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.setString(2, title);
            statement.setObject(3, LocalDate.parse(releaseDate));
            statement.setInt(4, status.getValue());
            statement.setInt(5, version);
            if (withDuration) {
                statement.setInt(6, 120);
            }
            statement.executeUpdate();
        }
    }

    private IPage<Movie> query(String title, MovieStatus status, LocalDate from, LocalDate to,
            long page, long size) throws Exception {
        try (SqlSession session = mapperFactory().openSession()) {
            return session.getMapper(MovieMapper.class).selectMoviePage(new Page<>(page, size), status,
                    title, from, to, null, null, "releaseDate", "asc");
        }
    }

    private SqlSessionFactory mapperFactory() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("movie-persistence-test", new JdbcTransactionFactory(), dataSource));
        GlobalConfigUtils.getGlobalConfig(configuration).setMetaObjectHandler(new MybatisPlusMetaObjectHandler());
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        configuration.addInterceptor(interceptor);
        String mapperResource = "mapper/MovieMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(mapperResource)) {
            new XMLMapperBuilder(input, configuration, mapperResource, configuration.getSqlFragments()).parse();
        }
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    // Exercises HTTP validation and real persistence; authorization is covered by ControllerRoutingTest.
    private MockMvc movieApi(SqlSession session) {
        MovieServiceImpl service = new MovieServiceImpl(session.getMapper(MovieMapper.class), new MovieConverter());
        return MockMvcBuilders.standaloneSetup(
                        new io.github.chenforegone.encoreticket.controller.admin.AdminMovieController(service),
                        new io.github.chenforegone.encoreticket.controller.user.UserMovieController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
