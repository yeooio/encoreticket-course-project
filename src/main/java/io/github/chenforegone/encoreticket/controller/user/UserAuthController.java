package io.github.chenforegone.encoreticket.controller.user;

import io.github.chenforegone.encoreticket.common.response.ApiResponse;
import io.github.chenforegone.encoreticket.model.dto.LoginRequest;
import io.github.chenforegone.encoreticket.model.vo.LoginVO;
import io.github.chenforegone.encoreticket.model.vo.SessionVO;
import io.github.chenforegone.encoreticket.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户端与管理端共用的认证接口入口。
 *
 * @author c1342
 */
@RestController
@RequestMapping("/auth")
public class UserAuthController {

    private final AuthService authService;

    public UserAuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录并建立新的登录态。
     *
     * @param request 登录参数
     * @return 访问令牌、用户资料和角色
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginVO>> login(
            @Valid @RequestBody LoginRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.of(authService.login(request)));
    }

    /**
     * 退出登录，使当前登录态失效。
     *
     * @return 空数据成功响应
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(jakarta.servlet.http.HttpServletRequest request) {
        authService.logout(request.getHeader("Authorization"));
        return ResponseEntity.ok(ApiResponse.of(null));
    }

    /**
     * 获取当前登录会话信息。
     *
     * @return 当前用户资料和角色快照
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<SessionVO>> me() {
        return ResponseEntity.ok(ApiResponse.of(authService.me()));
    }
}
