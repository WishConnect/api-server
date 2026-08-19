package com.wishconnect.global.operation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class AdminSystemServiceTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("관리자 로그 조회는 토큰·이메일·전화번호를 마스킹하고 마지막 N줄만 반환한다")
	void masksSensitiveLogDataAndTails() throws Exception {
		Path log = tempDir.resolve("app.log");
		Files.writeString(log, String.join("\n",
				"INFO first",
				"WARN email=user@example.com phone=010-1234-5678",
				"ERROR authorization=Bearer abc.def.ghi"));
		AdminSystemService service = new AdminSystemService(
				org.mockito.Mockito.mock(JdbcTemplate.class),
				org.mockito.Mockito.mock(RedisConnectionFactory.class));
		ReflectionTestUtils.setField(service, "logPath", log.toString());

		AdminLogResponse response = service.logs(2, null, null);

		assertThat(response.available()).isTrue();
		assertThat(response.lines()).hasSize(2);
		assertThat(response.lines().get(0)).contains("u***@example.com", "010-****-****");
		assertThat(response.lines().get(1)).contains("authorization=[REDACTED]")
				.doesNotContain("abc.def.ghi");
	}
}
