package com.sks.kb;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * pgvector {@code vector(1024)} 列的 MyBatis TypeHandler。
 *
 * <p><b>写入</b>（{@link #setNonNullParameter}）：把 {@code float[]} 拼成 pgvector 字面量字符串
 * {@code "[v1,v2,...]"}，{@code ps.setString} 发给 JDBC。SQL 里用显式 cast（如
 * {@code #{embedding, typeHandler=com.sks.kb.VectorTypeHandler}::vector}），PG 的 pgvector
 * 输入函数解析该字符串。null 值由 {@link BaseTypeHandler#setParameter} 自动调 {@code setNull}。
 *
 * <p><b>读取</b>（{@link #getNullableResult}）：pgvector 列经 pgjdbc {@code getString} 返回
 * {@code "[v1,v2,...]"} 字符串，本 handler 去掉首尾 {@code []} 后按逗号拆分为 {@code float[]}。
 * 本任务只写不读（KB CRUD 不回查 embedding），但读路径留好，供 Task 1.3 RAG 检索复用。
 *
 * <p><b>为什么不用 pgvector 官方 Java 驱动</b>（{@code com.pgvector:pgvector}）：YAGNI——本 handler
 * 不加依赖、不注册 {@code PGobject}，纯字符串互转即可覆盖写 + 读。后续若需要更高效传输可替换。
 */
@MappedTypes(float[].class)
public class VectorTypeHandler extends BaseTypeHandler<float[]> {

    @Override
    public void setNonNullParameter(
            PreparedStatement ps, int i, float[] parameter, JdbcType jdbcType) throws SQLException {
        StringBuilder sb = new StringBuilder(parameter.length * 8);
        sb.append('[');
        for (int j = 0; j < parameter.length; j++) {
            if (j > 0) {
                sb.append(',');
            }
            sb.append(parameter[j]);
        }
        sb.append(']');
        ps.setString(i, sb.toString());
    }

    @Override
    public float[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public float[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public float[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    /** 解析 pgvector 返回的 {@code "[v1,v2,...]"} 字符串为 {@code float[]}；null 或空返回 null。 */
    private static float[] parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String body = s.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isEmpty()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] arr = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            arr[i] = Float.parseFloat(parts[i].trim());
        }
        return arr;
    }
}
