package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.service.rules.TicketStatusResolver;

import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;
import io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException;
import io.github.chenforegone.encoreticket.converter.TicketConverter;
import io.github.chenforegone.encoreticket.mapper.TicketMapper;
import io.github.chenforegone.encoreticket.model.dto.TicketQueryRequest;
import io.github.chenforegone.encoreticket.model.query.TicketProjection;
import io.github.chenforegone.encoreticket.model.query.TicketQueryFilter;
import io.github.chenforegone.encoreticket.service.impl.TicketServiceImpl;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 票券查询的归属、完整性、参数及时间编排测试。
 *
 * @author c1342
 */
class TicketServiceImplTest {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private static final Instant INSTANT = Instant.parse("2026-10-19T04:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-10-19T12:00:00");
    private TicketMapper mapper;
    private java.time.Clock clock;
    private TicketServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(TicketMapper.class);
        clock = mock(java.time.Clock.class);
        when(clock.instant()).thenReturn(INSTANT);
        when(clock.getZone()).thenReturn(ZoneOffset.ofHours(-7));
        service = new TicketServiceImpl(mapper, new TicketConverter(new TicketStatusResolver()),
                VALIDATORS.getValidator(), clock);
    }

    @AfterAll
    static void closeValidation() {
        VALIDATORS.close();
    }

    @Test
    void listChecksIntegrityBeforeCountingAndUsesOneShanghaiNow() {
        TicketQueryRequest request = new TicketQueryRequest();
        request.setPage(Integer.MAX_VALUE);
        request.setPageSize(50);
        request.setStatus(" EXPIRED ");
        request.setMovieTitle("  电影%_!\\  ");
        request.setOrderNo("  ORDER-1  ");
        when(mapper.count(any())).thenReturn(1L);
        when(mapper.selectPage(any())).thenReturn(List.of(ticket()));

        var result = service.list("7", request);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items().getFirst().status()).isEqualTo("EXPIRED");
        ArgumentCaptor<TicketQueryFilter> integrity = ArgumentCaptor.forClass(TicketQueryFilter.class);
        ArgumentCaptor<TicketQueryFilter> query = ArgumentCaptor.forClass(TicketQueryFilter.class);
        var order = inOrder(mapper);
        order.verify(mapper).hasInvalidOwned(integrity.capture());
        order.verify(mapper).count(query.capture());
        order.verify(mapper).selectPage(query.getValue());
        assertThat(integrity.getValue().status()).isNull();
        assertThat(integrity.getValue().now()).isEqualTo(NOW);
        assertThat(query.getValue().now()).isSameAs(integrity.getValue().now());
        assertThat(query.getValue().userId()).isEqualTo(7);
        assertThat(query.getValue().offset()).isEqualTo(107374182300L);
        assertThat(query.getValue().movieTitlePrefix()).isEqualTo("电影%_!\\");
        assertThat(query.getValue().orderNo()).isEqualTo("ORDER-1");
        verify(clock, times(1)).instant();
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void invalidOwnedDataCannotBeHiddenByStatusOrEmptyPage() {
        TicketQueryRequest request = new TicketQueryRequest();
        request.setStatus("USED");
        when(mapper.hasInvalidOwned(any())).thenReturn(true);
        assertThatThrownBy(() -> service.list("7", request)).isInstanceOf(IllegalStateException.class);
        verify(mapper).hasInvalidOwned(argThat(filter -> filter.status() == null));
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void emptyAndOutOfRangePagesKeepRequestedPagination() {
        TicketQueryRequest request = new TicketQueryRequest();
        request.setPage(20);
        when(mapper.count(any())).thenReturn(3L);
        when(mapper.selectPage(any())).thenReturn(List.of());
        var page = service.list("7", request);
        assertThat(page.items()).isEmpty();
        assertThat(page.page()).isEqualTo(20);
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.total()).isEqualTo(3);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "-1", "+1", "01", "1.0", "1e2", " 1", "1 ", "abc", "１", "9223372036854775808"})
    void rejectsNoncanonicalIdsBeforeDatabaseAccess(String id) {
        assertThatThrownBy(() -> service.getDetail("7", id)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.getStatus("7", id)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.list(id, new TicketQueryRequest())).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(mapper);
        verify(clock, never()).instant();
    }

    @Test
    void revalidatesEveryDtoConstraintWithoutMvc() {
        assertThatThrownBy(() -> service.list("7", null)).isInstanceOf(InvalidRequestException.class);
        for (int field = 0; field < 8; field++) {
            TicketQueryRequest request = new TicketQueryRequest();
            switch (field) {
                case 0 -> request.setPage(null);
                case 1 -> request.setPage(0);
                case 2 -> request.setPageSize(null);
                case 3 -> request.setPageSize(0);
                case 4 -> request.setPageSize(51);
                case 5 -> request.setStatus("valid");
                case 6 -> request.setMovieTitle("影".repeat(256));
                case 7 -> request.setOrderNo("o".repeat(33));
            }
            assertThatThrownBy(() -> service.list("7", request)).isInstanceOf(InvalidRequestException.class);
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void ownedDetailIncludesCodeAndStatusHasSameTimeBoundary() {
        when(mapper.selectOwned(1, 7)).thenReturn(ticket());
        var detail = service.getDetail("7", "1");
        assertThat(detail.ticketCode()).isEqualTo("PRIVATE-CODE");
        assertThat(detail.unitPrice()).isEqualTo("88.00");
        assertThat(detail.status()).isEqualTo("EXPIRED");
        assertThat(detail.verifiedAt()).isNull();
        assertThat(detail.endTime()).isEqualTo("2026-10-19T12:00:00+08:00");
        assertThat(service.getStatus("7", "1").status()).isEqualTo("EXPIRED");
        verify(clock, times(2)).instant();
    }

    @Test
    void unknownAndOtherOwnersAreIndistinguishable() throws Exception {
        assertThatThrownBy(() -> service.getDetail("7", "1")).isInstanceOf(ResourceNotFoundException.class);
        when(mapper.selectOwned(1, 7)).thenReturn(replace(3, 8L));
        assertThatThrownBy(() -> service.getDetail("7", "1")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getStatus("7", "1")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void allRequiredProjectionFieldsAreCheckedOnEveryQuery() throws Exception {
        for (int index = 0; index < TicketProjection.class.getRecordComponents().length; index++) {
            if (index == 20) continue;
            TicketProjection broken = replace(index, null);
            when(mapper.selectOwned(1, 7)).thenReturn(broken);
            assertThatThrownBy(() -> service.getDetail("7", "1")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.getStatus("7", "1")).isInstanceOf(IllegalStateException.class);
            when(mapper.selectPage(any())).thenReturn(List.of(broken));
            assertThatThrownBy(() -> service.list("7", new TicketQueryRequest())).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void invalidStatesAndPricesFailEvenForTerminalTickets() throws Exception {
        for (TicketProjection broken : List.of(replace(13, 9), replace(14, 6), replace(14, 5),
                replace(19, new BigDecimal("-0.01")), replace(19, new BigDecimal("1.001")),
                replace(19, new BigDecimal("100000000.00")))) {
            when(mapper.selectOwned(1, 7)).thenReturn(broken);
            assertThatThrownBy(() -> service.getStatus("7", "1")).isInstanceOf(IllegalStateException.class);
        }
        TicketProjection used = replace(13, 1);
        when(mapper.selectOwned(1, 7)).thenReturn(used);
        assertThat(service.getStatus("7", "1").status()).isEqualTo("USED");
        when(mapper.selectOwned(1, 7)).thenReturn(replace(14, 4));
        assertThat(service.getStatus("7", "1").status()).isEqualTo("REFUNDING");
        when(mapper.selectOwned(Long.MAX_VALUE, 7)).thenReturn(replace(0, Long.MAX_VALUE));
        assertThat(service.getStatus("7", Long.toString(Long.MAX_VALUE)).status()).isEqualTo("EXPIRED");
    }

    @Test
    void onlyPaidOrRefundingOrdersCanHaveValidTickets() throws Exception {
        for (int order : List.of(0, 2, 3, 5, -1, 6)) {
            when(mapper.selectOwned(1, 7)).thenReturn(replace(14, order));
            assertThatThrownBy(() -> service.getDetail("7", "1")).isInstanceOf(IllegalStateException.class);
        }
        for (int terminal : List.of(1, 2)) {
            for (int order = 0; order <= 5; order++) {
                var base = replace(13, terminal);
                var ticket = new TicketProjection(base.id(), base.orderItemId(), base.orderId(), base.userId(),
                        base.movieId(), base.screeningId(), base.hallId(), base.screeningSeatId(), base.seatId(),
                        base.ticketCode(), base.orderNo(), base.movieTitle(), base.hallName(), terminal, order,
                        base.rowNumber(), base.seatNumber(), base.startTime(), base.endTime(), base.unitPrice(),
                        base.verifiedAt(), base.createdAt());
                when(mapper.selectOwned(1, 7)).thenReturn(ticket);
                assertThat(service.getStatus("7", "1").status()).isEqualTo(terminal == 1 ? "USED" : "VOID");
            }
        }
    }

    @Test
    void defaultClockCanBeReplacedWithoutChangingOtherClockBeans() {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(io.github.chenforegone.encoreticket.config.TicketQueryConfiguration.class);
        runner.run(context -> assertThat(context).hasSingleBean(java.time.Clock.class));
        java.time.Clock fixed = java.time.Clock.fixed(INSTANT, ZoneOffset.UTC);
        runner.withBean(java.time.Clock.class, () -> fixed)
                .run(context -> assertThat(context.getBean(java.time.Clock.class)).isSameAs(fixed));
    }

    private static TicketProjection ticket() {
        return new TicketProjection(1L, 2L, 3L, 7L, 4L, 5L, 6L, 8L, 9L,
                "PRIVATE-CODE", "ORDER-1", "电影", "一号厅", 0, 1, 2, 3,
                NOW.minusHours(2), NOW, new BigDecimal("88.00"), null, INSTANT.minusSeconds(60));
    }

    private static TicketProjection replace(int index, Object value) throws Exception {
        var components = TicketProjection.class.getRecordComponents();
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) values[i] = components[i].getAccessor().invoke(ticket());
        values[index] = value;
        Class<?>[] types = Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
        return TicketProjection.class.getDeclaredConstructor(types).newInstance(values);
    }
}
