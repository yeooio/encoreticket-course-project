package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.model.dto.ChangePasswordRequest;
import io.github.chenforegone.encoreticket.model.dto.RegisterRequest;
import io.github.chenforegone.encoreticket.model.dto.UpdateProfileRequest;
import io.github.chenforegone.encoreticket.model.vo.LoginVO;
import io.github.chenforegone.encoreticket.model.vo.NicknameSuggestionVO;
import io.github.chenforegone.encoreticket.model.vo.UserVO;

/**
 * 用户业务服务。
 *
 * @author c1342
 */
public interface UserService {

    /**
     * 注册用户并建立登录态。
     *
     * @param request 注册请求
     * @return 注册成功后的访问令牌、用户资料和角色
     */
    LoginVO register(RegisterRequest request);

    /**
     * 生成一个供用户确认的随机昵称候选，不保存到数据库。
     *
     * @return 随机昵称候选
     */
    NicknameSuggestionVO generateNicknameSuggestion();

    /**
     * 获取当前用户信息。
     *
     * @param userId 当前用户 ID
     * @return 用户资料视图
     */
    UserVO getCurrentUser(String userId);

    /**
     * 更新当前用户个人信息。
     *
     * @param userId  当前用户 ID
     * @param request 更新请求；只传需要修改的字段
     * @return 更新后的用户资料视图
     */
    UserVO updateProfile(String userId, UpdateProfileRequest request);

    /**
     * 修改当前用户密码。
     *
     * <p>成功后该用户的全部登录令牌将被失效，需要重新登录。</p>
     *
     * @param userId  当前用户 ID
     * @param request 密码修改请求
     */
    void changePassword(String userId, ChangePasswordRequest request);
}
