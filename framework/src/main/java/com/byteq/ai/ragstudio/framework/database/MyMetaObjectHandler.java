package com.byteq.ai.ragstudio.framework.database;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

import java.util.Date;

/**
 * MyBatis-Plus 元数据自动填充处理器
 *
 * <p>继承 MyBatis-Plus 的 {@link MetaObjectHandler} 接口，在实体类执行
 * 数据库插入或更新操作时，自动为指定字段填充值，减少业务代码中的重复赋值操作。</p>
 *
 * <p>自动填充策略：</p>
 * <ul>
 *   <li><b>插入时：</b>自动填充 {@code createTime}（当前时间）、{@code updateTime}（当前时间）、
 *       {@code deleted}（默认值 0，表示未删除）</li>
 *   <li><b>更新时：</b>自动填充 {@code updateTime}（当前时间，表示最后更新时间）</li>
 * </ul>
 *
 * <p>注意：实体类中对应的字段需加上 {@code @TableField(fill = FieldFill.INSERT)} 等注解才生效。</p>
 */
public class MyMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入操作时的自动填充逻辑
     * <p>使用 MyBatis-Plus 提供的 {@code strictInsertFill} 方法进行严格模式填充，
     * 仅在实体类对应字段值为 {@code null} 时才填充，避免覆盖已有值。</p>
     *
     * @param metaObject MyBatis 的元数据对象，包含待操作的实体类属性信息
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        // 自动填充创建时间（使用当前时间）
        strictInsertFill(metaObject, "createTime", Date::new, Date.class);
        // 自动填充更新时间（使用当前时间）
        strictInsertFill(metaObject, "updateTime", Date::new, Date.class);
        // 自动填充删除标记（0 表示未删除，1 表示已删除）
        strictInsertFill(metaObject, "deleted", () -> 0, Integer.class);
    }

    /**
     * 更新操作时的自动填充逻辑
     * <p>使用 {@code strictUpdateFill} 方法在每次更新时自动刷新 {@code updateTime} 字段，
     * 确保该字段始终记录最后一次修改时间。</p>
     *
     * @param metaObject MyBatis 的元数据对象，包含待操作的实体类属性信息
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        // 自动更新修改时间为当前时间
        strictUpdateFill(metaObject, "updateTime", Date::new, Date.class);
    }
}