package com.github.analyticshub.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * MyBatis Plus 元数据处理器
 * 自动填充创建时间和更新时间
 */
@Component
public class MyBatisPlusMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createdAt", Instant.class, Instant.now());
        this.strictInsertFill(metaObject, "updatedAt", Instant.class, Instant.now());
        this.strictInsertFill(metaObject, "lastActiveAt", Instant.class, Instant.now());
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", Instant.class, Instant.now());
        this.strictUpdateFill(metaObject, "lastActiveAt", Instant.class, Instant.now());
    }
}
