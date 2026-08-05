package com.wishconnect.global.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 오래 걸리는 운영 배치(외부 API 대량 호출 등)를 요청 스레드 밖에서 돌리기 위한 실행기.
 *
 * <p>단일 스레드 + 큐 1 로 둔 이유: 이 실행기를 쓰는 작업들은 외부 공공데이터 API 를 수백 번
 * 호출하므로 동시에 여러 번 돌면 상대 서버에 부담이 되고 DB 커넥션도 함께 잡아먹는다.
 * 실행 중 재요청은 서비스 레이어에서 걸러내고, 그래도 밀려들면 호출 스레드에서 거절되도록
 * 큐를 최소로 유지한다.
 */
@Configuration
public class AsyncConfig {

	@Bean
	public Executor academicInfoSyncExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(1);
		executor.setThreadNamePrefix("academic-sync-");
		// 종료 시 진행 중인 동기화가 DB 커넥션을 잡은 채 끊기지 않도록 완료를 기다린다.
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(60);
		executor.initialize();
		return executor;
	}
}
