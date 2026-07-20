package com.sks.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * app_user 的 Mapper：BaseMapper 提供通用 CRUD，{@link #findByPhone} 用于登录时按手机号查用户。
 * {@code @Mapper} 注解让主类的 {@code @MapperScan(annotationClass=Mapper.class)} 扫描到本接口。
 */
@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {

    @Select("SELECT * FROM app_user WHERE phone = #{phone}")
    AppUser findByPhone(String phone);
}
