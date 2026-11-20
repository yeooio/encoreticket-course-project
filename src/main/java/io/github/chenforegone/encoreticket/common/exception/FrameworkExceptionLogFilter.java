package io.github.chenforegone.encoreticket.common.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

/**
 * 对已验证会展开原始异常消息的 MVC/Tomcat 故障日志做定向替换。
 *
 * <p>保留日志来源、级别与受控诊断；其他日志和容器故障不受影响。
 * 配置由 Logback 在日志系统初始化时加载，不依赖 Spring Bean 或请求缓存。
 *
 * @author c1342
 */
public final class FrameworkExceptionLogFilter extends TurboFilter {

    private static final String RESOLVER_LOGGER =
            "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver";
    private static final String CONTAINER_LOGGER_PREFIX = "org.apache.catalina.core.ContainerBase.";

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format,
                              Object[] arguments, Throwable throwable) {
        Throwable failure = throwable;
        //处理两种写法,当异常通过占位符可变参数传入的时候进行读取
        if (failure == null && arguments != null && arguments.length > 0
                && arguments[arguments.length - 1] instanceof Throwable lastFailure) {
            failure = lastFailure;
        }
        //如果异常为空,直接放行
        if (failure == null || format == null) {
            return FilterReply.NEUTRAL;
        }
        // 只替换已验证的框架故障事件；未匹配不代表内容已通过安全检查。
        String safeEvent = safeEvent(logger.getName(), format);
        if (safeEvent == null) {
            return FilterReply.NEUTRAL;
        }

        // 同一 logger 重新走原有级别/路由规则；无 Throwable 的替代事件不会再次匹配本过滤器。
        logger.log(marker, FrameworkExceptionLogFilter.class.getName(), Level.toLocationAwareLoggerInteger(level),
                safeEvent + ExceptionLogSupport.safeStackTrace(failure), null, null);
        return FilterReply.DENY;
    }

    private static String safeEvent(String loggerName, String format) {
        if (RESOLVER_LOGGER.equals(loggerName) && format.startsWith("Failure in @ExceptionHandler ")) {
            return "MVC exception handler failed";
        }
        if (loggerName.startsWith(CONTAINER_LOGGER_PREFIX)) {
            if (format.startsWith("Servlet.service() for servlet ")) {
                return "Servlet request processing failed";
            }
            if (format.startsWith("Exception Processing [ErrorPage")) {
                return "Container error dispatch failed";
            }
        }
        return null;
    }
}
