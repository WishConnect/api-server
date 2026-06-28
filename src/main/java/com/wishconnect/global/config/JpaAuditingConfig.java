package com.wishconnect.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * {@code @CreatedDate} / {@code @LastModifiedDate} 자동 기록을 위한 JPA Auditing 활성화.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
