package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.model.dto.LoginRequest;
import io.github.chenforegone.encoreticket.model.vo.LoginVO;
import io.github.chenforegone.encoreticket.model.vo.SessionVO;

/**
 * 认证业务服务。
 *
 * @author c1342
 */
public interface AuthService {

    /**
     * 校验用户凭据并建立新的登录态。
     *
     * @param request 登录请求
     * @return 登录成功后的访问令牌、用户资料和角色
     */
    LoginVO login(LoginRequest request);

    /**
     * 兼容旧调用方：从已认证上下文撤销当前令牌，不执行全端撤销。
     */
    void logout();

    /** 从请求 Authorization 撤销当前令牌，不要求已认证身份。 */
    void logout(String authorization);

    /**
     * 获取当前登录会话信息。
     *
     * @return 当前用户资料和角色快照
     */
    SessionVO me();
}
