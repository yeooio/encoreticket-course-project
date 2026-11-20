package io.github.chenforegone.encoreticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.chenforegone.encoreticket.model.entity.Role;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色数据访问接口。
 *
 * @author c1342
 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {
}
