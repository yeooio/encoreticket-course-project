package io.github.chenforegone.encoreticket.controller.user;

import io.github.chenforegone.encoreticket.common.response.ApiResponse;
import io.github.chenforegone.encoreticket.model.dto.ChangePasswordRequest;
import io.github.chenforegone.encoreticket.model.dto.RegisterRequest;
import io.github.chenforegone.encoreticket.model.dto.UpdateProfileRequest;
import io.github.chenforegone.encoreticket.model.vo.LoginVO;
import io.github.chenforegone.encoreticket.model.vo.NicknameSuggestionVO;
import io.github.chenforegone.encoreticket.model.vo.UserVO;
import io.github.chenforegone.encoreticket.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户注册与个人资料接口入口，个人资料操作由客户端与管理端共用。
 *
 * @author c1342
 */
@RestController
@RequestMapping("/users")
public class UserAccountController {

    private final UserService userService;

    public UserAccountController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 注册用户并自动建立登录态。
     *
     * @param request 注册参数
     * @return 201 与访问令牌、用户资料和默认角色
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<LoginVO>> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(userService.register(request)));
    }

    /**
     * 生成一个供注册页或资料页使用的随机昵称候选。
     *
     * @return 随机昵称候选，不写入用户资料
     */
    @GetMapping("/nickname/suggestion")
    public ResponseEntity<ApiResponse<NicknameSuggestionVO>> nicknameSuggestion() {
        return ResponseEntity.ok(ApiResponse.of(userService.generateNicknameSuggestion()));
    }

    /**
     * 获取当前登录用户信息。
     *
     * @param userId 当前用户 ID（由 RedisTokenAuthenticationFilter 写入 SecurityContext）
     * @return 用户资料
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserVO>> getCurrentUser(
            @AuthenticationPrincipal String userId
    ) {
        return ResponseEntity.ok(ApiResponse.of(userService.getCurrentUser(userId)));
    }

    /**
     * 更新当前用户个人信息。
     *
     * @param userId  当前用户 ID
     * @param request 更新请求；只传需要修改的字段
     * @return 更新后的用户资料
     */
    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<UserVO>> updateProfile(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.of(userService.updateProfile(userId, request)));
    }

    /**
     * 修改当前用户密码。
     *
     * <p>成功后当前用户全部登录令牌失效，需要重新登录。</p>
     *
     * @param userId  当前用户 ID
     * @param request 密码修改请求
     * @return 包含 null data 的成功响应
     */
    @PutMapping("/me/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        userService.changePassword(userId, request);
        return ResponseEntity.ok(ApiResponse.of(null));
    }

    // TODO: GET /users/me/orders
    // TODO: GET /users/me/tickets
}
