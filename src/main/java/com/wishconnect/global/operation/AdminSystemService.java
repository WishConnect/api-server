package com.wishconnect.global.operation;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AdminSystemService {

	private static final int MAX_LOG_LINES = 500;
	private static final int MAX_LINE_LENGTH = 4000;
	private static final Pattern SECRET = Pattern.compile(
			"(?i)(password|token|secret|api[-_]?key|authorization)\\s*[=:]\\s*(?:bearer\\s+)?[^\\s,;]+",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern EMAIL = Pattern.compile(
			"([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
	private static final Pattern PHONE = Pattern.compile("01[016789][- ]?\\d{3,4}[- ]?\\d{4}");

	private final JdbcTemplate jdbcTemplate;
	private final RedisConnectionFactory redisConnectionFactory;

	@Value("${admin.console.log-path:/home/ubuntu/app/logs/app.log}")
	private String logPath;

	public AdminSystemStatusResponse status() {
		long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
		Runtime runtime = Runtime.getRuntime();
		long heapUsed = runtime.totalMemory() - runtime.freeMemory();
		long heapMax = runtime.maxMemory();
		return new AdminSystemStatusResponse(
				LocalDateTime.now(),
				new AdminSystemStatusResponse.Check("UP", 0, "uptime " + uptime + "ms"),
				checkDatabase(),
				checkRedis(),
				new AdminSystemStatusResponse.Memory(heapUsed, heapMax, percent(heapUsed, heapMax)),
				disk());
	}

	public AdminLogResponse logs(Integer requestedLines, String level, String keyword) {
		int limit = Math.min(Math.max(requestedLines == null ? 200 : requestedLines, 1), MAX_LOG_LINES);
		Path path = Path.of(logPath).toAbsolutePath().normalize();
		if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
			return new AdminLogResponse(LocalDateTime.now(), false,
					"설정된 애플리케이션 로그 파일을 읽을 수 없습니다.", List.of());
		}
		ArrayDeque<String> tail = new ArrayDeque<>(limit);
		try (var stream = Files.lines(path)) {
			stream.filter(line -> matches(line, level, keyword)).map(this::mask).forEach(line -> {
				if (tail.size() == limit) tail.removeFirst();
				tail.addLast(line.length() > MAX_LINE_LENGTH ? line.substring(0, MAX_LINE_LENGTH) + "..." : line);
			});
			return new AdminLogResponse(LocalDateTime.now(), true, null, new ArrayList<>(tail));
		} catch (IOException exception) {
			return new AdminLogResponse(LocalDateTime.now(), false,
					"로그를 읽는 중 오류가 발생했습니다.", List.of());
		}
	}

	private AdminSystemStatusResponse.Check checkDatabase() {
		long started = System.nanoTime();
		try {
			jdbcTemplate.queryForObject("SELECT 1", Integer.class);
			return check("UP", started, "PostgreSQL 연결 정상");
		} catch (RuntimeException exception) {
			return check("DOWN", started, exception.getClass().getSimpleName());
		}
	}

	private AdminSystemStatusResponse.Check checkRedis() {
		long started = System.nanoTime();
		try (RedisConnection connection = redisConnectionFactory.getConnection()) {
			String pong = connection.ping();
			return check("PONG".equalsIgnoreCase(pong) ? "UP" : "DOWN", started, "Redis " + pong);
		} catch (RuntimeException exception) {
			return check("DOWN", started, exception.getClass().getSimpleName());
		}
	}

	private AdminSystemStatusResponse.Check check(String status, long started, String detail) {
		return new AdminSystemStatusResponse.Check(status,
				Math.max(0, (System.nanoTime() - started) / 1_000_000), detail);
	}

	private AdminSystemStatusResponse.Disk disk() {
		try {
			Path path = Path.of(logPath).toAbsolutePath().normalize();
			Path target = Files.exists(path) ? path : Path.of(".").toAbsolutePath().normalize();
			FileStore store = Files.getFileStore(target);
			long total = store.getTotalSpace();
			long used = Math.max(0, total - store.getUsableSpace());
			return new AdminSystemStatusResponse.Disk(used, total, percent(used, total), target.toString());
		} catch (IOException exception) {
			return new AdminSystemStatusResponse.Disk(0, 0, 0, "unavailable");
		}
	}

	private boolean matches(String line, String level, String keyword) {
		String upper = line.toUpperCase(Locale.ROOT);
		if (StringUtils.hasText(level)) {
			String requested = level.trim().toUpperCase(Locale.ROOT);
			if ("ERROR".equals(requested) && !upper.contains("ERROR")) return false;
			if ("WARN".equals(requested) && !upper.contains("WARN") && !upper.contains("ERROR")) return false;
		}
		return !StringUtils.hasText(keyword)
				|| upper.contains(keyword.trim().toUpperCase(Locale.ROOT));
	}

	private String mask(String line) {
		String result = SECRET.matcher(line).replaceAll("$1=[REDACTED]");
		Matcher email = EMAIL.matcher(result);
		StringBuffer buffer = new StringBuffer();
		while (email.find()) {
			email.appendReplacement(buffer, Matcher.quoteReplacement(email.group(1) + "***@" + email.group(2)));
		}
		email.appendTail(buffer);
		return PHONE.matcher(buffer.toString()).replaceAll("010-****-****");
	}

	private int percent(long used, long total) {
		return total <= 0 ? 0 : (int) Math.min(100, Math.round(used * 100.0 / total));
	}
}
