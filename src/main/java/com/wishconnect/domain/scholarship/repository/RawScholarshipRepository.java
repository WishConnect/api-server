package com.wishconnect.domain.scholarship.repository;

import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/*
외부 API 원본 데이터(raw_scholarship)를 저장하고 조회하는 Repository입니다.
source + sourceId 조합으로 이미 수집한 원본인지 확인해 중복 저장을 막습니다.
 */
public interface RawScholarshipRepository extends JpaRepository<RawScholarship, Long> {

	Optional<RawScholarship> findBySourceAndSourceId(String source, String sourceId);

	long countByScholarship(Scholarship scholarship);

	/** 수집기 멱등 처리용: 같은 출처의 공지를 이미 수집했는지. */
	boolean existsBySourceAndSourceId(String source, String sourceId);

	/** 관리자 화면: 원본 수집 데이터의 파싱 상태 분포. */
	long countByParseStatus(ParseStatus parseStatus);

	/**
	 * LLM 파싱 대상: 아직 파싱되지 않은 대학 공고.
	 * source 가 UNIV_ 로 시작하는 것만 — 공공데이터(KOSAF 등)는 기존 방식으로 파싱하므로 제외한다.
	 */
	List<RawScholarship> findBySourceStartingWithAndParseStatusOrderByIdAsc(
			String sourcePrefix, ParseStatus parseStatus, Pageable pageable);

	/** 재파싱 대상: 상태와 무관하게 대학 공고 전체. 잘못 파싱된 기존 데이터를 덮어쓸 때 쓴다. */
	List<RawScholarship> findBySourceStartingWithOrderByIdAsc(String sourcePrefix, Pageable pageable);

	/**
	 * 재파싱 대상 중 <b>이 프롬프트로는 아직 안 돌린 것</b>.
	 *
	 * <p>이게 없으면 재파싱이 매번 id 오름차순 첫 100건을 다시 집는다. 실제로 배치를 세 번 돌렸는데
	 * 320회를 호출하고 처리한 공지는 100건이었다(같은 것을 3~5번씩). 프롬프트를 고쳤을 때만
	 * 다시 돌아야 하므로 버전으로 거른다.
	 */
	@Query("""
			select r from RawScholarship r
			 where r.source like concat(:sourcePrefix, '%')
			   and not exists (
			       select 1 from NoticeParseLog l
			        where l.rawScholarshipId = r.id and l.promptVersion = :promptVersion)
			 order by r.id asc
			""")
	List<RawScholarship> findReparseTargets(@Param("sourcePrefix") String sourcePrefix,
			@Param("promptVersion") String promptVersion, Pageable pageable);

	long countBySourceStartingWithAndParseStatus(String sourcePrefix, ParseStatus parseStatus);
}