package io.github.chenforegone.encoreticket.model.dto;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 票券查询输入契约测试。
 *
 * @author c1342
 */
class TicketQueryRequestTest {
    @Test
    void defaultsAndBlankFiltersCanBeBoundAsJavaBean() {
        TicketQueryRequest request = new TicketQueryRequest();
        request.setStatus("  ");
        request.setMovieTitle("  电影%_!\\  ");
        request.setOrderNo("  NO1  ");
        assertThat(request.getPage()).isEqualTo(1);
        assertThat(request.getPageSize()).isEqualTo(10);
        assertThat(request.getStatus()).isNull();
        assertThat(request.getMovieTitle()).isEqualTo("电影%_!\\");
        assertThat(request.getOrderNo()).isEqualTo("NO1");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }

    @Test
    void bindingRejectsFractionalPageAndAcceptsIntegerParameters() {
        var request = new TicketQueryRequest();
        var binder = new org.springframework.validation.DataBinder(request);
        var values = new org.springframework.beans.MutablePropertyValues();
        values.add("page", "1.5");
        values.add("pageSize", "50");
        values.add("status", "  ");
        binder.bind(values);
        assertThat(binder.getBindingResult().getFieldError("page")).isNotNull();
        assertThat(request.getPageSize()).isEqualTo(50);
        assertThat(request.getStatus()).isNull();
    }

    @Test
    void paginationStatusAndNormalizedLengthsAreValidated() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (int size : new int[]{1, 50}) {
                var request = new TicketQueryRequest();
                request.setPageSize(size);
                request.setStatus(" REFUNDING ");
                request.setMovieTitle("x".repeat(255));
                request.setOrderNo("x".repeat(32));
                assertThat(validator.validate(request)).isEmpty();
            }
            for (int size : new int[]{0, 51}) {
                var request = new TicketQueryRequest();
                request.setPageSize(size);
                assertThat(validator.validate(request)).extracting(v -> v.getPropertyPath().toString())
                        .contains("pageSize");
            }
            var invalid = new TicketQueryRequest();
            invalid.setPage(0);
            invalid.setPageSize(null);
            invalid.setStatus("valid");
            invalid.setMovieTitle("x".repeat(256));
            invalid.setOrderNo("x".repeat(33));
            assertThat(validator.validate(invalid)).extracting(v -> v.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("page", "pageSize", "status", "movieTitle", "orderNo");
            invalid.setPage(null);
            assertThat(validator.validate(invalid)).extracting(v -> v.getPropertyPath().toString())
                    .contains("page");
        }
    }
}
