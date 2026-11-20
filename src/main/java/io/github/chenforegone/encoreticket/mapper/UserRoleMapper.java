package io.github.chenforegone.encoreticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.chenforegone.encoreticket.model.entity.UserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户角色关系数据访问接口。
 *
 * @author c1342
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {

    @Select("""
            SELECT r.role_code
            FROM user_role ur
            JOIN role r ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
            """)
    List<String> selectRoleCodesByUserId(Long userId);
}
