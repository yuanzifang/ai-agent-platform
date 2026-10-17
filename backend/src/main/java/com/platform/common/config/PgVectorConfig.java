package com.platform.common.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PgVector 独立数据源（架构 8.5）：与 MySQL 主数据源隔离，仅向量读写使用。
 * 注意：不向容器暴露 DataSource Bean（避免 DataSourceAutoConfiguration 退避，MySQL 仍为默认主数据源），
 * 仅注册 pgJdbcTemplate 供 VectorRepository 使用。
 */
@Configuration
public class PgVectorConfig {

    @Bean
    public JdbcTemplate pgJdbcTemplate(@Value("${pgvector.url}") String url,
                                       @Value("${pgvector.username}") String username,
                                       @Value("${pgvector.password:}") String password) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(8);
        ds.setPoolName("pgvector-pool");
        return new JdbcTemplate(ds);
    }
}
