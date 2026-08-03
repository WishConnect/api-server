package com.wishconnect.domain.common.service;

import com.wishconnect.domain.common.client.AcademicInfoApiClient;
import com.wishconnect.domain.common.client.AcademicInfoApiClient.MajorItem;
import com.wishconnect.domain.common.client.AcademicInfoApiClient.SchoolItem;
import com.wishconnect.domain.common.dto.AcademicInfoSyncResponse;
import com.wishconnect.domain.common.dto.AcademicInfoSyncStatusResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 전국 대학/학과 공공데이터를 school, major 마스터 테이블에 저장한다.
 * 이미 존재하는 이름은 건너뛰어 여러 번 호출해도 중복 저장되지 않는다.
 *
 * <p>전체 동기화는 학교 1회 + 학교 수만큼의 학과 조회(약 377회)라 수 분이 걸린다.
 * 요청 스레드에서 그대로 돌리면 nginx 60초 타임아웃에 걸려 매번 504 가 나므로,
 * {@link #start()} 는 백그라운드 실행만 걸고 즉시 반환한다. 진행 상태는
 * {@link #status()} 로 확인한다.
 *
 * <p>DB 쓰기는 {@link AcademicInfoSyncWriteService} 가 청크 단위 트랜잭션으로 처리한다.
 * 외부 API 호출 구간에서는 커넥션을 잡지 않는다.
 */
@Service
@Slf4j
public class AcademicInfoSyncService {

	/** 한 트랜잭션에서 저장할 최대 건수. 트랜잭션을 짧게 끊어 커넥션 점유를 줄인다. */
	private static final int CHUNK_SIZE = 500;

	private final AcademicInfoApiClient academicInfoApiClient;
	private final AcademicInfoSyncWriteService writer;
	private final Executor executor;

	/** 동시 실행 방지. 외부 API 를 수백 번 호출하므로 중복 실행은 상대 서버에도 부담이다. */
	private final AtomicBoolean running = new AtomicBoolean(false);
	private volatile AcademicInfoSyncStatusResponse status = AcademicInfoSyncStatusResponse.neverRun();

	public AcademicInfoSyncService(
			AcademicInfoApiClient academicInfoApiClient,
			AcademicInfoSyncWriteService writer,
			@Qualifier("academicInfoSyncExecutor") Executor executor) {
		this.academicInfoApiClient = academicInfoApiClient;
		this.writer = writer;
		this.executor = executor;
	}

	/**
	 * 동기화를 백그라운드로 시작하고 현재 상태를 즉시 반환한다.
	 * 이미 실행 중이면 새로 시작하지 않고 진행 중인 작업의 상태를 그대로 돌려준다.
	 */
	public AcademicInfoSyncStatusResponse start() {
		if (!running.compareAndSet(false, true)) {
			log.info("Academic info sync already running. request ignored.");
			return status;
		}

		LocalDateTime startedAt = LocalDateTime.now();
		status = AcademicInfoSyncStatusResponse.running(startedAt, "동기화를 시작했습니다. 완료까지 수 분 걸립니다.");
		try {
			executor.execute(() -> runSync(startedAt));
		} catch (RejectedExecutionException e) {
			// 실행기가 거절하면 시작 자체가 안 된 것이므로 플래그를 되돌린다.
			running.set(false);
			status = AcademicInfoSyncStatusResponse.failed(startedAt, "동기화를 시작하지 못했습니다. 잠시 후 다시 시도해주세요.");
			log.warn("Academic info sync rejected by executor.", e);
		}
		return status;
	}

	/** 마지막(또는 진행 중) 동기화 상태. */
	public AcademicInfoSyncStatusResponse status() {
		return status;
	}

	private void runSync(LocalDateTime startedAt) {
		try {
			AcademicInfoSyncResponse result = sync();
			status = AcademicInfoSyncStatusResponse.succeeded(startedAt, result);
		} catch (Exception e) {
			// 백그라운드 실행이라 예외가 요청 응답으로 전달되지 않는다. 상태와 로그에 남긴다.
			log.error("Academic info sync failed.", e);
			status = AcademicInfoSyncStatusResponse.failed(startedAt, e.getMessage());
		} finally {
			running.set(false);
		}
	}

	/**
	 * 동기화 본체. 외부 API 조회는 트랜잭션 밖에서 수행하고, 저장만 청크 트랜잭션으로 넘긴다.
	 * (테스트·수동 실행에서 동기적으로 호출할 수 있도록 public 으로 둔다)
	 */
	public AcademicInfoSyncResponse sync() {
		List<SchoolItem> schools = academicInfoApiClient.fetchSchools();
		log.info("Academic info sync fetched schools. count={}", schools.size());
		List<MajorItem> majors = academicInfoApiClient.fetchMajors(schools);
		log.info("Academic info sync fetched majors. count={}", majors.size());

		int savedSchools = saveNew(
				schools, SchoolItem::name, writer.findExistingSchoolNames(), writer::saveSchoolChunk);
		int savedMajors = saveNew(
				majors, MajorItem::name, writer.findExistingMajorNames(), writer::saveMajorChunk);

		log.info("Academic info sync saved. schools={}, majors={}", savedSchools, savedMajors);
		return new AcademicInfoSyncResponse(schools.size(), savedSchools, majors.size(), savedMajors);
	}

	/**
	 * 이름 기준으로 신규 항목만 골라 청크 단위로 저장한다.
	 * 응답 안에서도 같은 이름이 여러 번 오므로(학교마다 같은 학과명) 이미 담은 이름도 함께 걸러낸다.
	 */
	private <T> int saveNew(
			List<T> items,
			Function<T, String> nameGetter,
			Set<String> existingNames,
			ToIntFunction<List<T>> chunkSaver) {

		Set<String> seen = new HashSet<>(existingNames);
		List<T> chunk = new ArrayList<>(CHUNK_SIZE);
		int saved = 0;

		for (T item : items) {
			String name = AcademicInfoNormalizer.normalize(nameGetter.apply(item));
			if (name == null || !seen.add(name)) {
				continue;
			}
			chunk.add(item);
			if (chunk.size() == CHUNK_SIZE) {
				saved += chunkSaver.applyAsInt(chunk);
				chunk = new ArrayList<>(CHUNK_SIZE);
			}
		}
		if (!chunk.isEmpty()) {
			saved += chunkSaver.applyAsInt(chunk);
		}
		return saved;
	}
}
