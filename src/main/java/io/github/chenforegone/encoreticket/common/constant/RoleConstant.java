package io.github.chenforegone.encoreticket.common.constant;

/**
 * 角色编码与角色主键常量。
 *
 * <p>角色主键与 {@code db/migration/V2__seed_roles.sql} 中的种子数据一致，
 * 业务代码引用本类常量，不直接书写角色编码或角色数字。
 *
 * @author c1342
 */
public final class RoleConstant {

    /** 管理员角色编码。 */
    public static final String ADMIN_CODE = "ADMIN";

    /** 检票人员角色编码。 */
    public static final String CHECKIN_STAFF_CODE = "CHECKIN_STAFF";

    /** 普通用户角色编码。 */
    public static final String USER_CODE = "USER";

    /** 管理员角色主键。 */
    public static final long ADMIN_ID = 1L;

    /** 检票人员角色主键。 */
    public static final long CHECKIN_STAFF_ID = 2L;

    /** 普通用户角色主键。 */
    public static final long USER_ID = 3L;

    private RoleConstant() {
    }
}
