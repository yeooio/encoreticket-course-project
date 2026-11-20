package io.github.chenforegone.encoreticket.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 可由 MVC 绑定的本人票券查询请求。
 *
 * @author c1342
 */
public class TicketQueryRequest {
    @NotNull @Min(1)
    private Integer page = 1;
    @NotNull @Min(1) @Max(50)
    private Integer pageSize = 10;
    @Pattern(regexp = "VALID|USED|VOID|EXPIRED|REFUNDING", message = "票券状态不合法")
    private String status;
    @Size(max = 255)
    private String movieTitle;
    @Size(max = 32)
    private String orderNo;

    public Integer getPage() { return page; }
    public void setPage(Integer page) { this.page = page; }
    public Integer getPageSize() { return pageSize; }
    public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = normalize(status); }
    public String getMovieTitle() { return movieTitle; }
    public void setMovieTitle(String movieTitle) { this.movieTitle = normalize(movieTitle); }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = normalize(orderNo); }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
