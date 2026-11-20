package io.github.chenforegone.encoreticket.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 审计时间字段自动填充处理器。
 *
 * <p>表结构要求 {@code create_time}、{@code update_time} 由应用写入。这里统一按
 * {@link Instant} 生成 UTC 时间，避免各处在业务代码里手写时间赋值。
 *
 * @author c1342
 */
@Component
public class MybatisPlusMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入时填充创建时间与更新时间。
     *
     * @param metaObject 待填充的实体元对象
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        Instant now = Instant.now();
        strictInsertFill(metaObject, "createTime", Instant.class, now);
        strictInsertFill(metaObject, "updateTime", Instant.class, now);
    }

    /**
     * 更新时填充更新时间。
     *
     * @param metaObject 待填充的实体元对象
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updateTime", Instant.class, Instant.now());
    }
}
