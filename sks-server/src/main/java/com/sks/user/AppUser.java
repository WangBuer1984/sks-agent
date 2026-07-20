package com.sks.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * C 端用户实体（表 {@code app_user}）。
 *
 * <p>{@code app_user} 是 PG 保留字 {@code user} 的避让命名。列名下划线风格由 MyBatis-Plus 的
 * {@code map-underscore-to-camel-case} 自动映射。表无 {@code deleted} 列，全局逻辑删除配置对本实体不生效。
 */
@TableName("app_user")
public class AppUser {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String phone;
    private String nickname;
    private String gender;
    private Integer age;
    private String city;
    private String industry;
    private String identity;
    private String style;
    private Integer weeklyGoal;
    private String defaultPlatform;
    private Integer profileCompleteness;
    private Integer tokenVersion;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getIndustry() { return industry; }
    public void setIndustry(String industry) { this.industry = industry; }

    public String getIdentity() { return identity; }
    public void setIdentity(String identity) { this.identity = identity; }

    public String getStyle() { return style; }
    public void setStyle(String style) { this.style = style; }

    public Integer getWeeklyGoal() { return weeklyGoal; }
    public void setWeeklyGoal(Integer weeklyGoal) { this.weeklyGoal = weeklyGoal; }

    public String getDefaultPlatform() { return defaultPlatform; }
    public void setDefaultPlatform(String defaultPlatform) { this.defaultPlatform = defaultPlatform; }

    public Integer getProfileCompleteness() { return profileCompleteness; }
    public void setProfileCompleteness(Integer profileCompleteness) {
        this.profileCompleteness = profileCompleteness;
    }

    public Integer getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(Integer tokenVersion) { this.tokenVersion = tokenVersion; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
