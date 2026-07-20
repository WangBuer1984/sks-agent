package com.sks.admin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * admin_user 的 Mapper：BaseMapper 提供通用 CRUD，自定义方法用于登录与种子回填。
 * {@code @Mapper} 注解让主类的 {@code @MapperScan(annotationClass=Mapper.class)} 扫描到本接口。
 */
@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUser> {

    /** 按用户名查管理端账号（登录用）。 */
    @Select("SELECT * FROM admin_user WHERE username = #{username}")
    AdminUser findByUsername(@Param("username") String username);

    /** 登录成功后更新 last_login_at。PG 无自动更新时间戳，需显式写入。 */
    @Update("UPDATE admin_user SET last_login_at = now() WHERE id = #{id}")
    void updateLastLoginAt(@Param("id") Long id);

    /**
     * 查找种子占位行：{@code __seed__} 或 password_hash 仍为 PLACEHOLDER 的任意行。
     * AdminSeedRunner 用此判断是否需要回填真实站长账号。
     */
    @Select("SELECT * FROM admin_user WHERE username = '__seed__' OR password_hash = 'PLACEHOLDER' LIMIT 1")
    AdminUser findPlaceholder();

    /**
     * Upsert 真实站长账号：username 冲突时更新 password_hash/name/status，便于重跑重新哈希。
     */
    @Insert(
            "INSERT INTO admin_user (username, password_hash, name, status) "
                    + "VALUES (#{username}, #{passwordHash}, #{name}, #{status}) "
                    + "ON CONFLICT (username) DO UPDATE SET "
                    + "password_hash = EXCLUDED.password_hash, "
                    + "name = EXCLUDED.name, "
                    + "status = EXCLUDED.status")
    void upsertSeed(
            @Param("username") String username,
            @Param("passwordHash") String passwordHash,
            @Param("name") String name,
            @Param("status") String status);
}
