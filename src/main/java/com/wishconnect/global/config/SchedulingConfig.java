package com.wishconnect.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스프링 스케줄링 활성화. 배치성 작업(@Scheduled)은 각 도메인의 scheduler 클래스에 둔다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
