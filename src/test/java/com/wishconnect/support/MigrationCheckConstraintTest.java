package com.wishconnect.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.wishconnect.global.operation.AdminJobFailureType;
import com.wishconnect.global.operation.AdminJobStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 마이그레이션 SQL 의 CHECK 값 목록이 엔티티 enum 과 정확히 같은지.
 *
 * <p>운영은 {@code ddl-auto: validate} 라 CHECK 내용을 보지 않는다. 값이 어긋나면 기동은 되고 그 값을 쓰는
 * INSERT·UPDATE 만 500 이 난다(2026-08 essay_status·scholarship_type 사고). SQL 을 고치거나 enum 에 값을
 * 더하면 이 테스트가 먼저 깨지게 한다.
 */
class MigrationCheckConstraintTest {

	private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

	static Set<String> checkValues(String file, String constraint) throws IOException {
		String sql = Files.readString(MIGRATIONS.resolve(file), StandardCharsets.UTF_8);
		// 주석(되돌리기 SQL)은 빼고 본다.
		String live = sql.lines().filter(line -> !line.trim().startsWith("--"))
				.reduce("", (a, b) -> a + "\n" + b);
		Matcher matcher = Pattern.compile("CONSTRAINT\\s+" + constraint + "\\s+CHECK\\s*\\(\\s*\\w+\\s+IN\\s*\\(([^)]*)\\)",
				Pattern.CASE_INSENSITIVE).matcher(live);
		assertThat(matcher.find()).as(file + " 에서 " + constraint + " 를 찾지 못했다").isTrue();
		Set<String> values = new LinkedHashSet<>();
		Matcher value = Pattern.compile("'([^']+)'").matcher(matcher.group(1));
		while (value.find()) {
			values.add(value.group(1));
		}
		return values;
	}

	static Set<String> enumNames(Class<? extends Enum<?>> type) {
		Set<String> names = new LinkedHashSet<>();
		Arrays.stream(type.getEnumConstants()).forEach(constant -> names.add(constant.name()));
		return names;
	}

	@Test
	@DisplayName("admin_job_run.status CHECK = AdminJobStatus")
	void jobStatus() throws IOException {
		assertThat(checkValues("V20261003_01__admin_job_failure.sql", "admin_job_run_status_check"))
				.containsExactlyInAnyOrderElementsOf(enumNames(AdminJobStatus.class));
	}

	@Test
	@DisplayName("admin_audit_log.action CHECK = AdminAction")
	void auditAction() throws IOException {
		assertThat(checkValues("V20261003_02__admin_audit_actions.sql", "admin_audit_log_action_check"))
				.containsExactlyInAnyOrderElementsOf(enumNames(com.wishconnect.global.audit.AdminAction.class));
	}

	@Test
	@DisplayName("admin_job_failure.failure_type CHECK = AdminJobFailureType")
	void failureType() throws IOException {
		assertThat(checkValues("V20261003_01__admin_job_failure.sql", "admin_job_failure_failure_type_check"))
				.containsExactlyInAnyOrderElementsOf(enumNames(AdminJobFailureType.class));
	}
}
