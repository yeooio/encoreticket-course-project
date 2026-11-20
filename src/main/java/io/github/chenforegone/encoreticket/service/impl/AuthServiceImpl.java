package io.github.chenforegone.encoreticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.chenforegone.encoreticket.common.exception.AuthenticationFailureException;
import io.github.chenforegone.encoreticket.common.exception.DependencyUnavailableException;
import io.github.chenforegone.encoreticket.common.exception.ExceptionLogSupport;
import io.github.chenforegone.encoreticket.common.exception.ForbiddenException;
import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;
import io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.converter.UserConverter;
import io.github.chenforegone.encoreticket.mapper.UserMapper;
import io.github.chenforegone.encoreticket.mapper.UserRoleMapper;
import io.github.chenforegone.encoreticket.model.dto.LoginRequest;
import io.github.chenforegone.encoreticket.model.entity.User;
import io.github.chenforegone.encoreticket.model.enums.UserStatus;
import io.github.chenforegone.encoreticket.model.vo.LoginVO;
import io.github.chenforegone.encoreticket.model.vo.SessionVO;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import io.github.chenforegone.encoreticket.security.token.TokenGenerator;
import io.github.chenforegone.encoreticket.service.AuthService;
import io.lettuce.core.RedisCommandTimeoutException;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 认证业务实现。
 *
 * @author c1342
 */
@Service
public class AuthServiceImpl implements AuthService {

    /* 仅用于不存在用户时执行同等成本的 BCrypt 校验，避免用户枚举时序差异。 */
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserConverter userConverter;
    private final PasswordEncoder passwordEncoder;
    private final TokenGenerator tokenGenerator;
    private final AuthTokenStore authTokenStore;

    public AuthServiceImpl(
            UserMapper userMapper,
            UserRoleMapper userRoleMapper,
            UserConverter userConverter,
            PasswordEncoder passwordEncoder,
            TokenGenerator tokenGenerator,
            AuthTokenStore authTokenStore
    ) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.userConverter = userConverter;
        this.passwordEncoder = passwordEncoder;
        this.tokenGenerator = tokenGenerator;
        this.authTokenStore = authTokenStore;
    }

    /** 校验凭据、加载角色快照并签发新的不透明登录令牌。 */
    @Override
    public LoginVO login(LoginRequest request) {
        validateRequest(request);

        //查询用户
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.username()));

        String encodedPassword;
        if (user == null || user.getPassword() == null) {
            encodedPassword = DUMMY_PASSWORD_HASH;
        } else {
            encodedPassword = user.getPassword();
        }

        //通过BCrypt校验密码是否正确
        boolean passwordMatches = passwordEncoder.matches(request.password(), encodedPassword);

        //校验用户是否存在、密码是否正确
        if (user == null || !passwordMatches) {
            throw new AuthenticationFailureException();
        }
        //校验用户状态是否正常
        if (user.getStatus() != UserStatus.NORMAL) {
            throw new ForbiddenException();
        }

        //加载用户角色,并通过redis进行缓存,然后返回到前端
        List<String> roles = loadRoles(user.getId());
        String accessToken = tokenGenerator.generate();
        try {
            authTokenStore.saveToken(user.getId().toString(), roles, accessToken, user.getAuthVersion());
        } catch (QueryTimeoutException exception) {
            throw new DependencyUnavailableException(exception);
        }
        return new LoginVO(accessToken, userConverter.toVO(user), roles);
    }

    /** 从安全上下文取出当前请求令牌，仅注销当前会话。 */
    @Override
    public void logout() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if(authentication == null || authentication.getPrincipal() == null){
            throw new AuthenticationFailureException();
        }
        Object details = authentication.getDetails();
        if (!(details instanceof String token) || token.isBlank()) {
            throw new AuthenticationFailureException();
        }
        authTokenStore.deleteToken(token);
    }

    @Override
    public void logout(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return;
        }
        String[] parts = authorization.trim().split("\\s+");
        if (parts.length != 2 || !"Bearer".equalsIgnoreCase(parts[0])
                || !parts[1].matches("[A-Za-z0-9\\-._~+/]+=*")) {
            return;
        }
        try {
            authTokenStore.deleteToken(parts[1]);
        } catch (RedisConnectionFailureException
                 | QueryTimeoutException
                 | RedisCommandTimeoutException exception) {
            var attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servlet) {
                ExceptionLogSupport.logOnce(
                        LoggerFactory.getLogger(AuthServiceImpl.class), servlet.getRequest(),
                        "退出登录态清理失败", exception);
            }
        }
    }

    /** 从安全上下文读取当前用户身份与角色，并补查用户资料返回会话视图。 */
    @Override
    public SessionVO me() {
        //获得authentication对象
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if(authentication == null || authentication.getPrincipal() == null){
            throw new AuthenticationFailureException();
        }
        //获得用户id
        String userId = authentication.getPrincipal().toString();
        //获得用户角色标识
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .map(auth -> auth.startsWith("ROLE_") ? auth.substring(5) : auth)
                .toList();
        //获得用户对象
        User user = userMapper.selectById(Long.parseLong(userId));
        if (user == null) {
            throw new ResourceNotFoundException();
        }
        return new SessionVO(userConverter.toVO(user), roles);
    }

    private List<String> loadRoles(Long userId) {
        List<String> roleCodes = userRoleMapper.selectRoleCodesByUserId(userId);
        if (roleCodes.isEmpty()) {
            throw new IllegalStateException("User role data is inconsistent");
        }
        return List.copyOf(roleCodes);
    }

    private static void validateRequest(LoginRequest request) {
        if (request == null
                || request.username() == null
                || request.username().isBlank()
                || request.password() == null
                || request.password().isBlank()) {
            throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        }
    }
}
