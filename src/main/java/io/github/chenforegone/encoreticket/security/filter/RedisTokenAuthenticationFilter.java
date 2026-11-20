package io.github.chenforegone.encoreticket.security.filter;

import io.github.chenforegone.encoreticket.common.exception.DependencyUnavailableException;
import io.github.chenforegone.encoreticket.mapper.UserMapper;
import io.github.chenforegone.encoreticket.model.entity.User;
import io.github.chenforegone.encoreticket.model.enums.UserStatus;
import io.github.chenforegone.encoreticket.security.token.AuthTokenSession;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Redis 令牌认证过滤器。
 *
 * <p>从请求头 {@code Authorization: Bearer <token>} 取出令牌，交由 {@link AuthTokenStore}
 * 反查对应的登录会话；查询成功即把用户和角色写入 {@code SecurityContext}，
 * 使后续请求以已认证身份继续处理。
 *
 * <p>本过滤器只解析身份。令牌缺失、格式错误或已失效时均放行，不直接写出响应；
 * 受保护路径的 401/403 由 Spring Security 授权层统一处理。Redis 访问异常不在此处吞掉，
 * 由异常桥接器交给统一错误处理，已识别的连接故障和超时返回503，不降级为匿名放行。
 *
 * @author c1342
 */
@Component
public class RedisTokenAuthenticationFilter extends OncePerRequestFilter {

    private final AuthTokenStore authTokenStore;
    private final UserMapper userMapper;

    @Autowired
    public RedisTokenAuthenticationFilter(AuthTokenStore authTokenStore,
            UserMapper userMapper) {
        this.authTokenStore = authTokenStore;
        this.userMapper = userMapper;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && "/auth/logout".equals(request.getRequestURI().substring(request.getContextPath().length()));
    }

    /**
     * 解析并校验请求头中的 Bearer 令牌，校验通过时填充安全上下文。
     *
     * @param request     当前 HTTP 请求
     * @param response    当前 HTTP 响应
     * @param filterChain 后续过滤器链
     * @throws ServletException Servlet 处理异常
     * @throws IOException      IO 异常
     */
    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        //获得请求头中携带认证标识
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        String[] authorizationParts = authorization.trim().split("\\s+");
        if (authorizationParts.length != 2 || !"Bearer".equalsIgnoreCase(authorizationParts[0])) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authorizationParts[1];
        if (token.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        // Redis 异常必须继续向上抛出，避免缓存不可用时降级成匿名身份。
        AuthTokenSession session;
        User user;
        try {
            session = authTokenStore.findSessionByToken(token);
            user = session == null ? null : userMapper.selectById(session.userId());
        } catch (QueryTimeoutException exception) {
            throw new DependencyUnavailableException(exception);
        }
        if (user != null && user.getStatus() == UserStatus.NORMAL
                && session.authVersion() != null && session.authVersion().equals(user.getAuthVersion())) {
            UsernamePasswordAuthenticationToken authenticationToken =
                    new UsernamePasswordAuthenticationToken(
                            session.userId(),
                            null,
                            toAuthorities(session.roles())
                    );
            // 保存当前请求的原始令牌，登出时复用 AuthTokenStore 删除这一枚令牌。
            authenticationToken.setDetails(token);
            SecurityContextHolder.getContext().setAuthentication(authenticationToken);
        }

        filterChain.doFilter(request, response);
    }

    private List<SimpleGrantedAuthority> toAuthorities(List<String> roles) {
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .filter(role -> role != null && !role.isBlank())
                .map(String::trim)
                .map(role -> role.regionMatches(true, 0, "ROLE_", 0, 5)
                        ? role.substring(5)
                        : role)
                .map(role -> "ROLE_" + role.toUpperCase(Locale.ROOT))
                .distinct()
                .map(SimpleGrantedAuthority::new)
                .toList();
    }
}
