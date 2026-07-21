package com.sks;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Mapper 扫描：扫描 {@code com.sks} 全部子包，但只注册标注了 {@link Mapper} 的接口。
 *
 * <p>Task 0.1 起初用 {@code "com.sks.**.mapper"}（约定 mapper 接口放 .mapper 子包），
 * 但 Task 0.4 起的简要要求 mapper 与 feature 类同包（{@code com.sks.auth} / {@code com.sks.user}）。
 * 改为按 {@link Mapper} 注解显式 opt-in，既兼容扁平 feature 包布局，又避免误把非 mapper 接口注册为代理。
 *
 * <p>{@link EnableScheduling}（Task 1.7）：开启 {@code @Scheduled} 支持——{@link com.sks.topic.HotTopicJob}
 * 的 {@code refreshHotTopics} 依赖此注解才会被调度（否则 {@code @Scheduled} 注解静默失效）。
 * 后续 {@code analyze_task} 轮询 / review {@code rejected} 扫描也复用同一开关。
 */
@SpringBootApplication
@MapperScan(basePackages = "com.sks", annotationClass = Mapper.class)
@EnableScheduling
public class SksServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SksServerApplication.class, args);
    }
}
