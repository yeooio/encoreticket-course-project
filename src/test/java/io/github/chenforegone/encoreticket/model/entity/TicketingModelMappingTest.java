package io.github.chenforegone.encoreticket.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.handlers.CompositeEnumTypeHandler;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.chenforegone.encoreticket.config.MybatisPlusMetaObjectHandler;
import io.github.chenforegone.encoreticket.mapper.SeatMapper;
import io.github.chenforegone.encoreticket.model.enums.HallStatus;
import io.github.chenforegone.encoreticket.model.enums.OrderStatus;
import io.github.chenforegone.encoreticket.model.enums.PaymentStatus;
import io.github.chenforegone.encoreticket.model.enums.RefundStatus;
import io.github.chenforegone.encoreticket.model.enums.ScreeningSeatStatus;
import io.github.chenforegone.encoreticket.model.enums.SeatStatus;
import io.github.chenforegone.encoreticket.model.enums.TicketStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 按数据库设计验证实体的持久化映射、审计填充和状态编码契约。
 *
 * @author c1342
 */
class TicketingModelMappingTest {

    @ParameterizedTest
    @MethodSource("entityMappings")
    void mapsOnlySchemaColumnsAndFillsAuditTimes(Class<?> entityType, String tableName,
                                                String expectedColumns) throws ReflectiveOperationException {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "model-contract");
        assistant.setCurrentNamespace(entityType.getName());
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, entityType);

        Set<String> columns = new HashSet<>();
        columns.add(tableInfo.getKeyColumn());
        tableInfo.getFieldList().forEach(field -> columns.add(field.getColumn()));
        assertEquals(tableName, tableInfo.getTableName());
        assertEquals(Set.of(expectedColumns.split(",")), columns);
        assertEquals(IdType.ASSIGN_ID, tableInfo.getIdType());

        Object entity = entityType.getConstructor().newInstance();
        MetaObject metaObject = SystemMetaObject.forObject(entity);
        Instant before = Instant.now();
        new MybatisPlusMetaObjectHandler().insertFill(metaObject);
        Instant createdAt = assertInstanceOf(Instant.class, metaObject.getValue("createTime"));
        assertFalse(createdAt.isBefore(before));
        assertFalse(createdAt.isAfter(Instant.now()));
        if (columns.contains("update_time")) {
            assertEquals(createdAt, metaObject.getValue("updateTime"));
        } else {
            assertFalse(metaObject.hasGetter("updateTime"));
        }
    }

    @Test
    void seatBaseMapperSqlQuotesReservedRowNumber() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(SeatMapper.class);
        Seat seat = new Seat();
        seat.setId(1L);
        seat.setHallId(2L);
        seat.setRowNumber(3);
        seat.setSeatNumber(4);

        String namespace = SeatMapper.class.getName() + ".";
        String insertSql = configuration.getMappedStatement(namespace + "insert")
                .getBoundSql(seat).getSql();
        String selectSql = configuration.getMappedStatement(namespace + "selectById")
                .getBoundSql(Map.of("id", seat.getId())).getSql();
        String updateSql = configuration.getMappedStatement(namespace + "updateById")
                .getBoundSql(Map.of("et", seat)).getSql();

        for (String sql : new String[]{insertSql, selectSql, updateSql}) {
            assertTrue(sql.contains("`row_number`"), sql);
            assertFalse(sql.replace("`row_number`", "").contains("row_number"), sql);
        }
        assertTrue(configuration.getMappedStatement(namespace + "insert").getBoundSql(seat)
                .getParameterMappings().stream().anyMatch(parameter -> parameter.getProperty().equals("rowNumber")));
    }

    // 期望列来自 docs/database/schema.sql，而非由实体字段反推。
    static Stream<Arguments> entityMappings() {
        return Stream.of(
                Arguments.of(Hall.class, "hall", "id,hall_name,hall_no,status,schedule_version,version,create_time,update_time"),
                Arguments.of(Seat.class, "seat", "id,hall_id,`row_number`,seat_number,status,version,create_time,update_time"),
                Arguments.of(Order.class, "orders",
                        "id,order_no,user_id,screening_id,total_amount,expires_at,status,version,create_time,update_time"),
                Arguments.of(ScreeningSeat.class, "screening_seat",
                        "id,seat_id,screening_id,status,order_id,create_time,update_time"),
                Arguments.of(OrderItem.class, "order_item",
                        "id,order_id,screening_seat_id,unit_price,create_time,update_time"),
                Arguments.of(Payment.class, "payment", "id,payment_no,order_id,amount,currency,status,version,attempt_id,create_time,update_time"),
                Arguments.of(Ticket.class, "ticket", "id,status,order_item_id,ticket_code,create_time,update_time"),
                Arguments.of(Refund.class, "refund",
                        "id,refund_no,order_id,payment_id,amount,status,source,attempt_no,next_run_at,lease_token,lease_until,create_time,update_time"),
                Arguments.of(TicketVerification.class, "ticket_verification",
                        "id,ticket_id,operator_id,device_code,verified_at,create_time")
        );
    }

    @ParameterizedTest
    @MethodSource("statusMappings")
    <E extends Enum<E>> void mapsStatusThroughJdbcAndPreservesApiCode(E status, int value, String code)
            throws ReflectiveOperationException, SQLException {
        Class<E> enumType = status.getDeclaringClass();
        String apiCode = switch (status) {
            case OrderStatus order -> order.getApiCode();
            case PaymentStatus payment -> payment.getValue();
            case TicketStatus ticket -> ticket.getApiCode();
            case HallStatus hall -> hall.getCode();
            case SeatStatus seat -> seat.getCode();
            case ScreeningSeatStatus seat -> seat.getCode();
            default -> status.name();
        };
        assertEquals(code, apiCode);
        assertSame(status, enumType.getMethod("fromValue", Integer.class).invoke(null, value));

        CompositeEnumTypeHandler<E> handler = new CompositeEnumTypeHandler<>(enumType);
        PreparedStatement statement = mock(PreparedStatement.class);
        handler.setParameter(statement, 1, status, JdbcType.TINYINT);
        verify(statement).setObject(1, value, Types.TINYINT);

        ResultSet rows = mock(ResultSet.class);
        when(rows.getObject("status", Integer.class)).thenReturn(value);
        assertSame(status, handler.getResult(rows, "status"));
    }

    // 期望编码来自 docs/domain/status-enum-mapping.md，包含 Java 名与接口 code 不同的订单状态。
    static Stream<Arguments> statusMappings() {
        return Stream.of(
                Arguments.of(HallStatus.ENABLED, 0, "ENABLED"),
                Arguments.of(HallStatus.DISABLED, 1, "DISABLED"),
                Arguments.of(SeatStatus.ENABLED, 0, "ENABLED"),
                Arguments.of(SeatStatus.DISABLED, 1, "DISABLED"),
                Arguments.of(OrderStatus.WAIT_PAY, 0, "PENDING_PAYMENT"),
                Arguments.of(OrderStatus.PAID, 1, "PAID"),
                Arguments.of(OrderStatus.CANCELLED, 2, "CANCELLED"),
                Arguments.of(OrderStatus.EXPIRED, 3, "EXPIRED"),
                Arguments.of(OrderStatus.REFUNDING, 4, "REFUNDING"),
                Arguments.of(OrderStatus.REFUNDED, 5, "REFUNDED"),
                Arguments.of(ScreeningSeatStatus.AVAILABLE, 0, "AVAILABLE"),
                Arguments.of(ScreeningSeatStatus.HELD, 1, "HELD"),
                Arguments.of(ScreeningSeatStatus.SOLD, 2, "SOLD"),
                Arguments.of(ScreeningSeatStatus.DISABLED, 3, "DISABLED"),
                Arguments.of(PaymentStatus.PENDING, 0, "PENDING"),
                Arguments.of(PaymentStatus.SUCCESS, 1, "SUCCESS"),
                Arguments.of(PaymentStatus.FAILED, 2, "FAILED"),
                Arguments.of(TicketStatus.VALID, 0, "VALID"),
                Arguments.of(TicketStatus.USED, 1, "USED"),
                Arguments.of(TicketStatus.VOID, 2, "VOID"),
                Arguments.of(RefundStatus.PENDING, 0, "PENDING"),
                Arguments.of(RefundStatus.SUCCESS, 1, "SUCCESS"),
                Arguments.of(RefundStatus.FAILED, 2, "FAILED")
        );
    }

    @ParameterizedTest
    @MethodSource("statusParsers")
    void explicitStatusParsingRejectsNullAndUnknownValues(Function<Integer, ?> parser) {
        assertThrows(IllegalArgumentException.class, () -> parser.apply(null));
        assertThrows(IllegalArgumentException.class, () -> parser.apply(-1));
        assertThrows(IllegalArgumentException.class, () -> parser.apply(99));
    }

    static Stream<Function<Integer, ?>> statusParsers() {
        return Stream.of(HallStatus::fromValue, SeatStatus::fromValue, OrderStatus::fromValue,
                ScreeningSeatStatus::fromValue, PaymentStatus::fromValue, TicketStatus::fromValue,
                RefundStatus::fromValue);
    }
}
