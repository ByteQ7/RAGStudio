package com.byteq.ai.ragstudio.framework.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.byteq.ai.ragstudio.framework.database.MyMetaObjectHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * 数据库持久层配置类
 *
 * <p>配置 MyBatis-Plus 相关插件和扩展组件，当前包括：</p>
 * <ul>
 *   <li>PostgreSQL 分页插件：自动拦截分页查询，生成适配 PostgreSQL 语法的分页 SQL</li>
 *   <li>元数据自动填充处理器：在插入和更新时自动填充公共字段（如 createTime、updateTime）</li>
 * </ul>
 */
@Configuration
public class DataBaseConfiguration {

    /**
     * MyBatis-Plus 分页插件
     * <p>配置 PostgreSQL 数据库类型的分页拦截器，在查询时自动拦截并添加 LIMIT/OFFSET 分页语句。
     * 使用 {@code PageHelper} 式分页调用时依赖此配置生效。</p>
     *
     * @return MyBatis-Plus 拦截器实例
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

    /**
     * MyBatis-Plus 元数据自动填充处理器
     * <p>注册 {@link MyMetaObjectHandler} 到 Spring 容器，在实体类执行 insert 或 update
     * 时自动填充 {@code createTime}、{@code updateTime}、{@code deleted} 等公共字段，
     * 减少重复编码。</p>
     *
     * @return 元数据自动填充处理器实例
     */
    @Bean
    public MetaObjectHandler myMetaObjectHandler() {
        return new MyMetaObjectHandler();
    }
}
