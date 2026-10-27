package com.wishconnect.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 관리자 콘솔 스크립트(작업 기록 표시 admin-audit.js)의 순수 함수 테스트를 node 로 돌린다.
 *
 * <p>콘솔은 빌드 도구 없는 정적 JS 라 자바 테스트로는 라벨·값 형식·목록 대조를 검사할 수 없다.
 * {@code src/test/resources/admin-js/*.test.mjs} 를 {@code node --test} 로 실행해 결과를 이 테스트의 성공·실패로 옮긴다.
 * node(18 이상)가 없는 환경에서는 건너뛴다(GitHub Actions ubuntu 러너에는 기본으로 있다).
 */
class AdminConsoleScriptTest {

	private static final Path SCRIPT_TESTS = Path.of("src/test/resources/admin-js/audit.test.mjs");

	@Test
	@DisplayName("작업 기록 화면 스크립트: 라벨·값 형식·내용 기준 행 대조·모르는 action·등록 내용(node --test)")
	void auditScript() throws Exception {
		Assumptions.assumeTrue(nodeAvailable(), "node 가 없어 콘솔 스크립트 테스트를 건너뜀");
		Process process = new ProcessBuilder("node", "--test", SCRIPT_TESTS.toString())
				.redirectErrorStream(true).start();
		boolean finished = process.waitFor(60, TimeUnit.SECONDS);
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!finished) {
			process.destroyForcibly();
		}
		assertThat(finished).as("node --test 가 60초 안에 끝나야 한다").isTrue();
		assertThat(process.exitValue()).as(output).isZero();
		assertThat(output).contains("# fail 0");
	}

	private static boolean nodeAvailable() {
		try {
			Process process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
			String version = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
				return false;
			}
			int major = Integer.parseInt(version.replaceFirst("^v", "").split("\\.")[0]);
			return major >= 18;
		} catch (IOException | InterruptedException | RuntimeException e) {
			return false;
		}
	}
}
