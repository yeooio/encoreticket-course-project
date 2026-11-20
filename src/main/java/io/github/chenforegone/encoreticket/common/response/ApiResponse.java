package io.github.chenforegone.encoreticket.common.response;

/**
 * 成功响应的统一包装。
 *
 * @param data 业务数据；无数据操作时为 {@code null}
 * @param <T> 业务数据类型
 */
public record ApiResponse<T>(T data) {

    /**
     * 创建成功响应。
     *
     * @param data 业务数据
     * @param <T> 业务数据类型
     * @return 包装后的成功响应
     */
    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data);
    }
}
