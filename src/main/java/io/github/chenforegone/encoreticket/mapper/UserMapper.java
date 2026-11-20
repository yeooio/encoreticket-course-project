package io.github.chenforegone.encoreticket.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.chenforegone.encoreticket.model.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户数据访问接口。
 *
 * @author c1342
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    default int updatePasswordByCas(Long id, long oldAuthVersion, String newEncodedPwd) {
        User patch = new User();
        patch.setPassword(newEncodedPwd);
        patch.setAuthVersion(Math.incrementExact(oldAuthVersion));
        return update(patch, new LambdaUpdateWrapper<User>()
                .eq(User::getId, id)
                .eq(User::getAuthVersion, oldAuthVersion)
        );
    }


    @Select("SELECT * FROM sys_user WHERE id = #{id} FOR UPDATE")
    User selectByIdForUpdate(@Param("id") Long id);

    /** 判断用户名是否已存在。 */
    default boolean existsByUsername(String username) {
        return selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)) > 0;
    }

    /** 判断邮箱是否已存在。 */
    default boolean existsByEmail(String email) {
        return selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email)) > 0;
    }

    /** 判断手机号是否已存在。 */
    default boolean existsByPhone(String phone) {
        return selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, phone)) > 0;
    }
}
