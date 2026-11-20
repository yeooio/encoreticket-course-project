package io.github.chenforegone.encoreticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.github.chenforegone.encoreticket.common.constant.RoleConstant;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.*;
import io.github.chenforegone.encoreticket.converter.UserConverter;
import io.github.chenforegone.encoreticket.mapper.UserMapper;
import io.github.chenforegone.encoreticket.mapper.UserRoleMapper;
import io.github.chenforegone.encoreticket.model.dto.ChangePasswordRequest;
import io.github.chenforegone.encoreticket.model.dto.RegisterRequest;
import io.github.chenforegone.encoreticket.model.dto.UpdateProfileRequest;
import io.github.chenforegone.encoreticket.model.entity.User;
import io.github.chenforegone.encoreticket.model.entity.UserRole;
import io.github.chenforegone.encoreticket.model.vo.LoginVO;
import io.github.chenforegone.encoreticket.model.vo.NicknameSuggestionVO;
import io.github.chenforegone.encoreticket.model.vo.UserVO;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import io.github.chenforegone.encoreticket.security.token.TokenGenerator;
import io.github.chenforegone.encoreticket.service.UserService;
import io.github.chenforegone.encoreticket.utils.DefaultProfileGenerator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 用户业务实现。
 *
 * @author c1342
 */
@Service
public class UserServiceImpl implements UserService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserServiceImpl.class);
    private static final List<String> DEFAULT_ROLES = List.of(RoleConstant.USER_CODE);

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserConverter userConverter;
    private final DefaultProfileGenerator profileGenerator;
    private final PasswordEncoder passwordEncoder;
    private final TokenGenerator tokenGenerator;
    private final AuthTokenStore authTokenStore;

    public UserServiceImpl(
            UserMapper userMapper,
            UserRoleMapper userRoleMapper,
            UserConverter userConverter,
            DefaultProfileGenerator profileGenerator,
            PasswordEncoder passwordEncoder,
            TokenGenerator tokenGenerator,
            AuthTokenStore authTokenStore
    ) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.userConverter = userConverter;
        this.profileGenerator = profileGenerator;
        this.passwordEncoder = passwordEncoder;
        this.tokenGenerator = tokenGenerator;
        this.authTokenStore = authTokenStore;
    }

    /** 注册即登录。所有持久化和角色关联写入在同一事务内完成。 */
    @Override
    @Transactional
    public LoginVO register(RegisterRequest request) {
        //判断当前用户名和密码是否重复
        validatePasswordNotUsername(request);
        //校验当前用户名,邮箱,手机号是否已经存在
        ensureUnique(request);
        //对用户密码进行Bcrypt加密
        String encodedPassword = passwordEncoder.encode(request.password());
        //生成用户信息
        String nickname = request.nickname();
        String avatar = request.avatar() != null ? request.avatar() : profileGenerator.generateAvatar();
        User user = User.register(
                request.username(),
                encodedPassword,
                nickname,
                avatar
        );
        user.setEmail(request.email());
        user.setPhone(request.phone());

        try {
            userMapper.insert(user);
            UserRole userRole = new UserRole();
            userRole.setUserId(user.getId());
            userRole.setRoleId(RoleConstant.USER_ID);
            userRoleMapper.insert(userRole);
        } catch (DuplicateKeyException exception) {
            // 预检与并发请求之间仍可能撞唯一索引，以 409 作为最终边界。
            throw new ConflictException(ErrorCode.RESOURCE_CONFLICT);
        }

        String accessToken = tokenGenerator.generate();
        removeTokenOnRollback(accessToken);
        try {
            authTokenStore.saveToken(user.getId().toString(), DEFAULT_ROLES, accessToken, user.getAuthVersion());
        } catch (QueryTimeoutException exception) {
            throw new DependencyUnavailableException(exception);
        }
        return new LoginVO(accessToken, userConverter.toVO(user), DEFAULT_ROLES);
    }

    @Override
    public NicknameSuggestionVO generateNicknameSuggestion() {
        return new NicknameSuggestionVO(profileGenerator.generateNickname());
    }

    private void ensureUnique(RegisterRequest request) {
        if (userMapper.existsByUsername(request.username())) {
            throw new ConflictException(ErrorCode.RESOURCE_CONFLICT);
        }
        if (request.email() != null && userMapper.existsByEmail(request.email())) {
            throw new ConflictException(ErrorCode.RESOURCE_CONFLICT);
        }
        if (request.phone() != null && userMapper.existsByPhone(request.phone())) {
            throw new ConflictException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    /** Redis 写入失败回滚 MySQL；MySQL 提交失败时补偿删除已经写入的令牌。 */
    private void removeTokenOnRollback(String token) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        try {
                            authTokenStore.deleteToken(token);
                        } catch (RuntimeException exception) {
                            // 不打印异常内容，避免驱动异常回显 Redis 命令中的会话数据。
                            LOGGER.error("Could not remove registration session after rollback: {}",
                                    exception.getClass().getSimpleName());
                        }
                    }
                }
            });
        }
    }

    private void validatePasswordNotUsername(RegisterRequest request) {
        if (request.username() != null && request.username().equalsIgnoreCase(request.password())) {
            throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    @Override
    public UserVO getCurrentUser(String userId) {
        User user = userMapper.selectById(userId);
        if(user == null){
            throw new ResourceNotFoundException();
        }
        return userConverter.toVO(user);
    }

    @Override
    @Transactional
    public UserVO updateProfile(String userId, UpdateProfileRequest request) {
        User user = userMapper.selectById(userId);
        if(user == null){
            throw new ResourceNotFoundException();
        }
        User patch = new User();
        patch.setId(user.getId());
        if (request.nickname() != null) {
            patch.setNickname(request.nickname());
            user.setNickname(request.nickname());
        }
        if (request.avatar() != null) {
            patch.setAvatar(request.avatar());
            user.setAvatar(request.avatar());
        }
        userMapper.updateById(patch);
        return userConverter.toVO(user);
    }

    @Override
    @Transactional(rollbackFor = {Exception.class})
    public void changePassword(String userId, ChangePasswordRequest request) {
        //判断用户是否存在
        User user = userMapper.selectById(Long.parseLong(userId));
        if(user == null){
            throw new ResourceNotFoundException();
        }
        //判断旧密码是否正确
        if (!passwordEncoder.matches(request.oldPassword(), user.getPassword())) {
            throw new BusinessRuleException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        if (request.newPassword().equalsIgnoreCase(user.getUsername())) {
            throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        }
        String encodedNewPassword = passwordEncoder.encode(request.newPassword());
        int rows = userMapper.updatePasswordByCas(user.getId(), user.getAuthVersion(), encodedNewPassword);
        if (rows != 1) {
            throw new BusinessRuleException(ErrorCode.RESOURCE_CONFLICT);
        }
        try {
            authTokenStore.deleteAllTokensForUser(userId);
        } catch (QueryTimeoutException exception) {
            throw new DependencyUnavailableException(exception);
        }
    }

}
