package com.sks.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * app_user 的 Mapper：BaseMapper 提供通用 CRUD，{@link #findByPhone} 用于登录时按手机号查用户，
 * {@link #findByPhoneTail} 用于管理端按手机尾号（后 4-6 位）模糊搜用户。
 * {@code @Mapper} 注解让主类的 {@code @MapperScan(annotationClass=Mapper.class)} 扫描到本接口。
 */
@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {

    @Select("SELECT * FROM app_user WHERE phone = #{phone}")
    AppUser findByPhone(String phone);

    /** 按手机尾号（后 4-6 位）后缀匹配，管理端「尾号搜索 → 多人逐一确认 → 开通」入口。 */
    @Select("SELECT * FROM app_user WHERE phone LIKE '%' || #{phoneTail} ORDER BY id")
    List<AppUser> findByPhoneTail(@Param("phoneTail") String phoneTail);
}
