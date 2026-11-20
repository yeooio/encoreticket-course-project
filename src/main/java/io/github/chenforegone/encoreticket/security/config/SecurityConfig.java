package io.github.chenforegone.encoreticket.security.config;

import io.github.chenforegone.encoreticket.security.filter.ExceptionBridgeFilter;
import io.github.chenforegone.encoreticket.security.filter.RedisTokenAuthenticationFilter;
import io.github.chenforegone.encoreticket.security.filter.RequestIdFilter;
import io.github.chenforegone.encoreticket.security.handler.JsonAccessDeniedHandler;
import io.github.chenforegone.encoreticket.security.handler.JsonAuthenticationEntryPoint;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;

/**
 * Spring Security 安全配置。
 *
 * <p>采用无状态会话：服务端不创建 {@code HttpSession}，认证状态完全依赖请求头中的
 * Bearer 令牌。登录、注册和业务文档列出的游客只读查询接口对匿名访问开放；
 * 其余请求一律要求已认证；
 * 认证失败与权限不足均返回 JSON 响应体，而非默认的跳转或 HTML 错误页。
 *
 * @author c1342
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 用户密码编码器。密码只在用户服务中编码，数据库始终保存 BCrypt 密文。
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 构建安全过滤器链。
     *
     * <p>过滤器顺序（由外到内）：{@link RequestIdFilter} 置于安全上下文过滤器之前，
     * 确保后续所有组件都能读到 requestId；{@link RedisTokenAuthenticationFilter}
     * 置于用户名密码过滤器之前，在任何授权判断之前完成令牌认证。
     *
     * @param requestIdFilter 请求追踪 ID 过滤器
     * @param http            Spring Security 的 HTTP 安全构建器
     * @param tokenFilter     Redis 令牌认证过滤器
     * @param entryPoint      未认证时返回 JSON 401 的入口点
     * @param handler         已认证但无权限时返回 JSON 403 的处理器
     * @return 配置完成的安全过滤器链
     * @throws Exception 构建过滤器链失败时抛出
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            RequestIdFilter requestIdFilter,
            ExceptionBridgeFilter exceptionBridgeFilter,
            HttpSecurity http,
            RedisTokenAuthenticationFilter tokenFilter,
            JsonAuthenticationEntryPoint entryPoint,
            JsonAccessDeniedHandler handler
    ) throws Exception {
        return http
                // 认证不依赖 Cookie 会话，不存在 CSRF 攻击面，故关闭该防护
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        // 先放行容器内部的错误分派；普通 /error 仍要求认证。
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        // 登录与注册允许匿名访问，其余接口默认要求已认证
                        .requestMatchers(HttpMethod.POST, "/auth/login", "/users/register", "/auth/logout")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/images/default-avatar.svg")
                        .permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/images/default-avatar.svg")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/users/nickname/suggestion")
                        .permitAll()
                        // 电影与场次的只读入口对游客开放；携带有效令牌时仍会先完成身份解析。
                        .requestMatchers(
                                HttpMethod.GET,
                                "/movies",
                                "/movies/*",
                                "/screenings",
                                "/screenings/*",
                                "/screenings/*/seats"
                        )
                        .permitAll()
                        .requestMatchers(HttpMethod.PATCH, "/users/me")
                        .authenticated()
                        .requestMatchers("/admin/**")
                        .hasRole("ADMIN")
                        .requestMatchers("/ticket-verifications/**")
                        .hasRole("CHECKIN_STAFF")
                        .anyRequest()
                        .authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(handler)
                )
                .addFilterBefore(
                        requestIdFilter,
                        SecurityContextHolderFilter.class
                )
                .addFilterAfter(
                        exceptionBridgeFilter,
                        RequestIdFilter.class
                )
                .addFilterBefore(
                        tokenFilter,
                        UsernamePasswordAuthenticationFilter.class
                )
                .build();
    }

    /** 请求销毁发生在同步、错误和异步响应处理完成之后。 */
    @Bean
    ServletListenerRegistrationBean<RequestIdFilter> requestSummaryListener(RequestIdFilter filter) {
        return new ServletListenerRegistrationBean<>(filter);
    }

    /** 防止请求追踪过滤器被 Servlet 容器和 Security 链重复注册。 */
    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration(RequestIdFilter filter) {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** 防止异常桥接过滤器被 Servlet 容器和 Security 链重复注册。 */
    @Bean
    FilterRegistrationBean<ExceptionBridgeFilter> exceptionBridgeFilterRegistration(
            ExceptionBridgeFilter filter
    ) {
        FilterRegistrationBean<ExceptionBridgeFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** 防止令牌过滤器被 Servlet 容器和 Security 链重复注册。 */
    @Bean
    FilterRegistrationBean<RedisTokenAuthenticationFilter> tokenFilterRegistration(
            RedisTokenAuthenticationFilter filter
    ) {
        FilterRegistrationBean<RedisTokenAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

}

