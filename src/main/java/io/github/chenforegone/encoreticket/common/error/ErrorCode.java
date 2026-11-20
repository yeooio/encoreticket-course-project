package io.github.chenforegone.encoreticket.common.error;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码枚举，统一维护错误码、HTTP 状态及默认提示文案。
 *
 * @author c1342
 */
public enum ErrorCode {

    UNAUTHORIZED("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "未登录或登录已过期"),
    FORBIDDEN("FORBIDDEN", HttpStatus.FORBIDDEN, "没有执行该操作的权限"),
    INVALID_ARGUMENT("INVALID_ARGUMENT", HttpStatus.BAD_REQUEST, "请求参数不合法"),
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND, "请求的资源不存在"),
    BUSINESS_RULE_VIOLATION("BUSINESS_RULE_VIOLATION", HttpStatus.UNPROCESSABLE_ENTITY, "当前操作不符合业务规则"),
    RESOURCE_CONFLICT("RESOURCE_CONFLICT", HttpStatus.CONFLICT, "资源已存在或状态发生冲突"),
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", HttpStatus.METHOD_NOT_ALLOWED, "不支持当前 HTTP 方法"),
    UNSUPPORTED_MEDIA_TYPE("UNSUPPORTED_MEDIA_TYPE", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持当前请求媒体类型"),
    PAYMENT_NOT_FOUND("PAYMENT_NOT_FOUND", HttpStatus.NOT_FOUND, "支付记录不存在"),
    PAYMENT_STATE_CONFLICT("PAYMENT_STATE_CONFLICT", HttpStatus.CONFLICT, "支付状态发生冲突"),
    PAYMENT_ALREADY_SUCCESS("PAYMENT_ALREADY_SUCCESS", HttpStatus.CONFLICT, "支付已经成功"),
    PAYMENT_AMOUNT_MISMATCH("PAYMENT_AMOUNT_MISMATCH", HttpStatus.UNPROCESSABLE_ENTITY, "支付金额不一致"),
    PAYMENT_ORDER_NOT_PAYABLE("PAYMENT_ORDER_NOT_PAYABLE", HttpStatus.CONFLICT, "订单当前不可支付"),
    PAYMENT_IDEMPOTENCY_CONFLICT("PAYMENT_IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT, "支付请求标识冲突"),
    PAYMENT_RESULT_UNKNOWN("PAYMENT_RESULT_UNKNOWN", HttpStatus.CONFLICT, "支付结果确认中"),
    PAYMENT_RETRY_NOT_ALLOWED("PAYMENT_RETRY_NOT_ALLOWED", HttpStatus.CONFLICT, "当前支付不允许重试"),
    REFUND_STATE_CONFLICT("REFUND_STATE_CONFLICT", HttpStatus.CONFLICT, "退款状态不允许当前操作"),
    REFUND_CUTOFF_REACHED("REFUND_CUTOFF_REACHED", HttpStatus.CONFLICT, "已达到退票截止时间"),
    TICKET_ALREADY_USED("TICKET_ALREADY_USED", HttpStatus.CONFLICT, "票券已核销"),
    TICKET_VOID("TICKET_VOID", HttpStatus.CONFLICT, "票券已作废"),
    ORDER_NOT_ADMISSIBLE("ORDER_NOT_ADMISSIBLE", HttpStatus.CONFLICT, "订单当前不可入场"),
    SCREENING_STATUS_CONFLICT("SCREENING_STATUS_CONFLICT", HttpStatus.CONFLICT, "场次当前不可入场"),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT, "请求标识与操作内容冲突"),
    TICKET_SCREENING_MISMATCH("TICKET_SCREENING_MISMATCH", HttpStatus.UNPROCESSABLE_ENTITY, "票券不属于当前场次"),
    ADMISSION_NOT_OPEN("ADMISSION_NOT_OPEN", HttpStatus.UNPROCESSABLE_ENTITY, "尚未开放入场"),
    ADMISSION_ENDED("ADMISSION_ENDED", HttpStatus.UNPROCESSABLE_ENTITY, "入场已结束"),
    HTTP_CLIENT_ERROR("HTTP_CLIENT_ERROR", HttpStatus.BAD_REQUEST, "请求无法处理"),
    MOVIE_ALREADY_OFFLINE("MOVIE_ALREADY_OFFLINE", HttpStatus.CONFLICT, "电影已经下架"),
    MOVIE_ALREADY_RELEASED("MOVIE_ALREADY_RELEASED", HttpStatus.CONFLICT, "电影已经上架"),
    MOVIE_VERSION_CONFLICT("MOVIE_VERSION_CONFLICT", HttpStatus.CONFLICT, "电影已被其他管理员修改，请刷新后重试"),
    MOVIE_TITLE_RELEASE_DATE_CONFLICT(
            "MOVIE_TITLE_RELEASE_DATE_CONFLICT", HttpStatus.CONFLICT, "相同标题和上映日期的电影已经存在"),
    MOVIE_PUBLISH_TIME_INVALID(
            "MOVIE_PUBLISH_TIME_INVALID", HttpStatus.UNPROCESSABLE_ENTITY, "自动发行时间不合法"),
    MOVIE_NOT_ONLINE("MOVIE_NOT_ONLINE", HttpStatus.CONFLICT, "电影当前不在上架状态"),
    MOVIE_SCREENING_CONFLICT("MOVIE_SCREENING_CONFLICT", HttpStatus.CONFLICT, "上映日期与已有场次冲突"),
    SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "服务暂时不可用，请稍后重试"),
    INTERNAL_ERROR("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(String code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
