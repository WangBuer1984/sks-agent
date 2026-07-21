package com.sks.topic;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 选题 Mapper。
 *
 * <p>{@link BaseMapper#insert} 可直接用于本实体（无非标准类型列，纯 varchar/text + timestamptz 默认值）。
 * 自定义 {@link #findById} 带 user_id 过滤（IDOR 防护，§5.1——不泄露「存在但不属于你」）。
 */
@Mapper
public interface TopicMapper extends BaseMapper<Topic> {

    /** 按 id + user_id 取选题（IDOR 防护）；跨用户返回 null。 */
    @Select("SELECT * FROM topic WHERE id = #{id} AND user_id = #{userId}")
    Topic findById(@Param("id") long id, @Param("userId") long userId);

    /** 当前用户的全部选题（按创建时间倒序），供创作页选择。 */
    @Select("SELECT * FROM topic WHERE user_id = #{userId} ORDER BY created_at DESC, id DESC")
    List<Topic> listByUser(@Param("userId") long userId);
}
