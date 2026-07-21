package com.sks;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 所有需要真实数据库的服务级测试的基类。
 *
 * <p>用 Testcontainers 拉起 {@code pgvector/pgvector:pg16}（与生产同方言、同扩展），通过 {@link
 * DynamicPropertySource} 把 JDBC 连接指向容器，Flyway 在启动时自动跑 V1/V2 迁移。
 * <strong>不用 H2</strong>——保证 SQL 方言（interval、timestamptz、vector）与生产一致。
 *
 * <p>{@link Transactional} 让每个测试方法结束后回滚，保证跨测试隔离。
 *
 * <p>容器采用「共享静态实例」模式（Testcontainers 官方推荐）：在 static 块里 {@code start()} 一次，
 * 整个 JVM 生命周期内复用。不用 {@code @Testcontainers}/{@code @Container}——后者按测试类管理生命周期，
 * 当多个测试类各跑一遍时会在第一个类的 {@code afterAll} 停掉容器，导致第二个类的上下文连不上同一个端口。
 * 静态共享实例绕开 JUnit 扩展，所有子类共用同一容器、同一端口。
 */
@SpringBootTest
@Transactional
public abstract class AbstractDbTest {

    private static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16").withReuse(true);
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // 测试专用 JWT 密钥：≥32 字节，避开 JwtConfig 的「空 / 占位」fail-fast 守卫。
        // 仅用于 @SpringBootTest 上下文，不进入生产环境。
        registry.add("JWT_SECRET_USER", () -> "test-user-secret-32bytes-min-xxxxxxxxxx");
        registry.add("JWT_SECRET_ADMIN", () -> "test-admin-secret-32bytes-min-xxxxxxxxx");
    }
}
