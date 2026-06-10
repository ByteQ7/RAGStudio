package com.byteq.ai.ragstudio.knowledge.dao.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * PostgreSQL jsonb 类型处理器
 * <p>
 * 自定义 MyBatis TypeHandler，用于 Java String 类型与 PostgreSQL jsonb 类型之间的相互转换。
 * 写入时将字符串包装为 PGobject（jsonb 类型），读取时直接以字符串形式返回。
 * 用于处理文档分块参数配置等 JSON 格式字段的持久化。
 * </p>
 */
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    /**
     * 设置非空参数
     * <p>将 Java String 转换为 PostgreSQL jsonb 类型。</p>
     *
     * @param ps        预处理语句
     * @param i         参数索引
     * @param parameter 参数字符串值
     * @param jdbcType  JDBC 类型
     * @throws SQLException SQL 异常
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        PGobject jsonObject = new PGobject();
        jsonObject.setType("jsonb");
        jsonObject.setValue(parameter);
        ps.setObject(i, jsonObject);
    }

    /**
     * 根据列名获取可空结果
     *
     * @param rs         结果集
     * @param columnName 列名
     * @return 字符串结果
     * @throws SQLException SQL 异常
     */
    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    /**
     * 根据列索引获取可空结果
     *
     * @param rs          结果集
     * @param columnIndex 列索引
     * @return 字符串结果
     * @throws SQLException SQL 异常
     */
    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    /**
     * 根据列索引获取可空结果（存储过程调用）
     *
     * @param cs          可调用语句
     * @param columnIndex 列索引
     * @return 字符串结果
     * @throws SQLException SQL 异常
     */
    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
