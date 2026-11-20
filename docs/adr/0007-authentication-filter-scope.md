# 认证过滤器只解析身份，拒绝交给授权层

状态：accepted

## 背景

`RedisTokenAuthenticationFilter` 原先在令牌格式错误或查不到时直接写出 401 响应。这使它在令牌无效时无条件拒绝，包括对公开接口的访问：前端拦截器一旦统一携带过期令牌，连登录接口都会被 401 顶回，用户无法重新登录。

## 决策

- 认证过滤器只负责解析 `Authorization` 头、查询令牌缓存、把身份与角色写入 `SecurityContext`；令牌缺失、格式错误或已失效时一律放行，不写出响应。
- 拒绝由授权层统一负责：路径级规则在 `authorizeHttpRequests`，方法级规则用 `@PreAuthorize` 并由 `@EnableMethodSecurity` 启用。未认证访问受保护资源返回 401，已认证但权限不足返回 403，两者都由 `ExceptionTranslationFilter` 决定。
- 公开白名单只影响路径级授权，不绕过令牌解析：白名单接口携带有效令牌时同样能拿到当前用户身份。
- Redis 不可用时按 fail-closed 处理，抛 5xx，不得降级为匿名身份放行受保护资源。

## 影响

- 前端不再需要为"令牌过期"单独分支，统一按 401 跳转登录。
- 代价是无法区分"令牌无效"与"未登录"，两者都返回 `UNAUTHORIZED`。
