package com.riemann.agent.infrastructure.neo4j;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import jakarta.annotation.PreDestroy;

/**
 * Neo4j（OceanBase 知识图谱）连接配置，仅在 oceanbase profile 下生效。
 *
 * <p>沿用 SeekDB 的“阻塞客户端 + 响应式包裹”模式：Driver 本身是阻塞的，
 * 查询在 {@link Neo4jRepository} 中通过 {@code Mono.fromCallable(...).subscribeOn(boundedElastic())}
 * 包到弹性调度器，调用方视角仍是非阻塞的。</p>
 */
@Configuration
@Profile("oceanbase")
public class Neo4jConfig {

    private Driver driver;

    @Bean
    public Driver neo4jDriver(@Value("${spring.neo4j.uri}") String uri,
                              @Value("${spring.neo4j.user}") String user,
                              @Value("${spring.neo4j.password}") String password) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
        return this.driver;
    }

    @Bean
    public Neo4jRepository neo4jRepository(Driver driver) {
        return new Neo4jRepository(driver);
    }

    @PreDestroy
    public void close() {
        if (driver != null) {
            driver.close();
        }
    }
}
