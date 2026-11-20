package io.github.chenforegone.encoreticket.common.exception;

import io.github.chenforegone.encoreticket.common.constant.RequestIdConstant;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 输出可定位故障的类型、受控原因、诊断字段与堆栈，不展开任意异常消息或请求正文。
 *
 * <p>去重信息只保存在当前请求；异常图、栈帧、上下文及日志总长度均设有上限。
 * 本工具不改变第三方框架和容器自身的日志行为。
 *
 * @author c1342
 */
public final class ExceptionLogSupport {

    //当前异常处理标识key
    private static final String LOG_STATE_ATTRIBUTE = ExceptionLogSupport.class.getName() + ".state";
    //异常cause解析深度
    private static final int MAX_DEPTH = 8;
    //单次日志采集异常的最大数量
    private static final int MAX_THROWABLES = 16;
    //全局异常做大的实例对象
    private static final int MAX_RECORDED_THROWABLES = 64;
    //单条异常栈保存的最大的栈帧消息
    private static final int MAX_FRAMES = 32;
    //单条日志最大的长度
    private static final int MAX_LOG_LENGTH = 16_384;
    //阶段标记文本
    private static final String TRUNCATED = "\n[diagnostic truncated]";

    private ExceptionLogSupport() {
    }

    /**
     * 同一请求中的同一异常只记录一次；没有异常对象时仅记录一次安全的请求上下文。
     *
     * <p>用于同步 Servlet 请求，同一请求须顺序调用；不保证同一请求并发调用时的线程安全。
     */
    public static void logOnce(Logger log, HttpServletRequest request, String event, Throwable exception) {
        //从请求中获得当前异常处理的状态
        Object existing = request.getAttribute(LOG_STATE_ATTRIBUTE);
        //判断是否为LogState实例对象,如果是,使用模式匹配,否则生成一个新的LogState独享
        LogState state = existing instanceof LogState logState ? logState : new LogState();
        //将当前异常处理状态设置到请求中
        request.setAttribute(LOG_STATE_ATTRIBUTE, state);
        boolean skip;
        //如果异常为null,判断当前异常是否没有记录
        if (exception == null) {
            skip = state.contextRecorded;
        } else {
            //如果异常已经进行记录,直接返回
            boolean alreadyRecorded = state.recorded.contains(exception);
            //如果当前异常记录数量达到上限直接返回
            boolean reachLimit = state.recorded.size() >= MAX_RECORDED_THROWABLES;
            skip = alreadyRecorded || reachLimit;
        }
        if (skip) {
            return;
        }
        //获取当前请求的URI
        Object originalPath = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        //判断请求是否为空,不为空转换为String类型对象
        String path = originalPath instanceof String value ? value : request.getRequestURI();
        //获得请求HTTP方法名
        Object originalMethod = request.getAttribute(RequestDispatcher.ERROR_METHOD);
        //判断请求是否为空,不为空转换为String类型对象
        String method = originalMethod instanceof String value ? value : request.getMethod();
        //构建异常摘要
        StringBuilder diagnostic = new StringBuilder();
        append(diagnostic, sanitizeLogText(event, 120)
                + ", requestId=" + sanitizeLogText(request.getAttribute(RequestIdConstant.ATTRIBUTE_NAME), 128)
                + ", method=" + sanitizeLogText(method, 16)
                + ", path=" + sanitizeLogText(path, 512));
        //设置当前这种空异常处理状态为已经进行处理
        if (exception == null) {
            state.contextRecorded = true;
        } else {
            //创建异常过滤器,防止记录重复异常,IdentityHashMap根据对象内存判断是否相等
            Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            //想异常概述中添加器cause链
            appendThrowable(diagnostic, exception, "", 0, visited, state.recorded);
        }
        // 只传入已筛选的字符串，不让日志框架展开原始 Throwable 的 message/cause。
        log.error(diagnostic.toString());
    }

    /** 框架日志过滤器复用同一套有界、安全堆栈格式，不依赖请求上下文。 */
    public static String safeStackTrace(Throwable exception) {
        StringBuilder diagnostic = new StringBuilder();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Throwable> recorded = Collections.newSetFromMap(new IdentityHashMap<>());
        appendThrowable(diagnostic, exception, "", 0, visited, recorded);
        return diagnostic.toString();
    }

    private static void appendThrowable(StringBuilder diagnostic, Throwable exception, String caption,
                                        int depth, Set<Throwable> visited, Set<Throwable> recorded) {
        // 已写满且仍有异常节点待输出时，补充截断提示。
        if (diagnostic.length() >= MAX_LOG_LENGTH) {
            append(diagnostic, TRUNCATED);
            return;
        }
        //如果异常链深度超出限制直接返回
        if (depth >= MAX_DEPTH || visited.size() >= MAX_THROWABLES) {
            append(diagnostic, "\n[exception graph truncated]");
            return;
        }
        //获得当前异常类
        String type = exception.getClass().getName();
        //判断当前异常对象是否已经进行处理如果已经处理发生循环依赖直接返回
        if (!visited.add(exception) || recorded.contains(exception)) {
            append(diagnostic, "\n" + caption + type + " [already recorded]");
            return;
        }
        //记录当前异常对象
        if (recorded.size() < MAX_RECORDED_THROWABLES) {
            recorded.add(exception);
        }
        //追加异常摘要
        append(diagnostic, "\n" + caption + type);
        appendDiagnosticDetails(diagnostic, exception);
        //获得异常堆栈
        StackTraceElement[] frames = exception.getStackTrace();
        for (int index = 0; index < Math.min(frames.length, MAX_FRAMES); index++) {
            // 已写满且仍有栈帧待输出时，补充截断提示。
            if (diagnostic.length() >= MAX_LOG_LENGTH) {
                append(diagnostic, TRUNCATED);
                return;
            }
            //获得当前的一个堆栈
            StackTraceElement frame = frames[index];
            String location;
            //确认栈帧标识
            if (frame.isNativeMethod()) {
                //标记为本地方法
                location = "Native Method";
            } else if (frame.getFileName() == null) {
                //如果文件名为空,标记为未知源
                location = "Unknown Source";
            } else {
                //去除脏字符
                location = sanitizeLogText(frame.getFileName(), 256) + ":" + frame.getLineNumber();
            }
            //追加摘要信息
            append(diagnostic, "\n\tat " + sanitizeLogText(frame.getClassName(), 256) + "."
                    + sanitizeLogText(frame.getMethodName(), 128) + "(" + location + ")");
        }
        //返回栈帧的过长截断信息
        if (frames.length > MAX_FRAMES) {
            append(diagnostic, "\n\t[stack frames truncated]");
        }
        //递归添加cause信息
        Throwable cause = exception.getCause();
        if (cause != null) {
            appendThrowable(diagnostic, cause, "Caused by: ", depth + 1, visited, recorded);
        }
        //递归添加suppressed异常信息
        for (Throwable suppressed : exception.getSuppressed()) {
            if (diagnostic.length() >= MAX_LOG_LENGTH || visited.size() >= MAX_THROWABLES) {
                append(diagnostic, "\n[suppressed exceptions truncated]");
                break;
            }
            appendThrowable(diagnostic, suppressed, "Suppressed: ", depth + 1, visited, recorded);
        }
    }

    private static void appendDiagnosticDetails(StringBuilder diagnostic, Throwable exception) {
        //如果异常是ApplicationException则追加异常码
        if (exception instanceof ApplicationException applicationException) {
            append(diagnostic, ", code=" + applicationException.getErrorCode().getCode()
                    + ", reason=" + applicationException.getErrorCode().getDefaultMessage());
        }
        //如果异常是SQL异常,追加sqlState和vendorCode
        if (exception instanceof SQLException sqlException) {
            String sqlState = sqlException.getSQLState();
            //正则校验SQLState格式：A-Z、0-9，正好5个字符（标准JDBC SQLState规范）
            if (sqlState != null && sqlState.matches("[A-Z0-9]{5}")) {
                append(diagnostic, ", sqlState=" + sqlState);
            }
            //数据库厂商原生错误码（mysql/oracle的error code
            append(diagnostic, ", vendorCode=" + sqlException.getErrorCode());
        }
        //Jackson序列化/反序列化异常（JSON解析报错）
        if (exception instanceof JacksonException jacksonException) {
            TokenStreamLocation location = jacksonException.getLocation();
            if (location != null) {
                //只拿行列号，不输出原始JSON（防止敏感数据泄露）
                if (location.getLineNr() > 0) {
                    append(diagnostic, ", line=" + location.getLineNr());
                }
                if (location.getColumnNr() > 0) {
                    append(diagnostic, ", column=" + location.getColumnNr());
                }
            }
        }
    }

    /** 仅替换控制字符并限制文本长度，不识别或脱敏密码、令牌等内容。 */
    private static String sanitizeLogText(Object value, int maxLength) {
        //如果不是String,或者是空字符串返回-
        if (!(value instanceof String text) || text.isEmpty()) {
            return "-";
        }
        //构建StringBuilder同时预先匹配容量
        StringBuilder sanitized = new StringBuilder(Math.min(text.length(), maxLength));
        //遍历字符串
        for (int index = 0; index < Math.min(text.length(), maxLength); index++) {
            char character = text.charAt(index);
            //去除脏字符
            sanitized.append(Character.isISOControl(character) || character == '\u2028' || character == '\u2029'
                    ? '?' : character);
        }
        return sanitized.toString();
    }

    //防止单词异常摘要的长度过大
    private static void append(StringBuilder diagnostic, String text) {
        //判断预留长度
        int remaining = MAX_LOG_LENGTH - diagnostic.length();
        //如果当前读文本长度小于预留长度直接追加
        if (text.length() <= remaining) {
            diagnostic.append(text);
        } else {
            //获得当前截断标识的长度
            int contentEnd = Math.max(0, MAX_LOG_LENGTH - TRUNCATED.length());
            //如果当前文本长度大于剩余长度,直接截断长处的部分
            if (diagnostic.length() > contentEnd) {
                diagnostic.setLength(contentEnd);
            } else {
                //否则追加能追加的信息
                diagnostic.append(text, 0, contentEnd - diagnostic.length());
            }
            //追加截断标识
            diagnostic.append(TRUNCATED);
        }
    }

    /**
     * 请求内的有界身份集合，不根据异常 equals/hashCode 判断是否已经记录。
     *
     * @author c1342
     */
    private static final class LogState {
        private final Set<Throwable> recorded = Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean contextRecorded;
    }
}
