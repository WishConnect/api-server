package com.wishconnect.domain.scholarship.repository;

import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import java.util.Collection;
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

	/**
	 * 특정 공지만 골라 다시 파싱한다.
	 *
	 * <p>프롬프트는 그대로인데 <b>LLM 에게 주는 본문이 달라졌을 때</b> 필요하다. 추출기를 고치면
	 * 같은 공지라도 결과가 달라지는데, 평소 대상 선정은 프롬프트 버전으로 거르기 때문에
	 * 이미 파싱한 건 다시 잡히지 않는다. 그렇다고 프롬프트 버전을 올리면 안 바뀐 것을 바뀌었다고
	 * 기록하는 셈이라, 나중에 "무엇이 언제부터 달라졌는지" 를 되짚을 수 없게 된다.
	 */
	List<RawScholarship> findByIdInOrderByIdAsc(Collection<Long> ids);

	/**
	 * 공공데이터 중 <b>지금 모집 중이고 조건이 비어 있는</b> 것.
	 *
	 * <p>마감된 3,571건은 사용자에게 안 보이므로 크레딧을 쓰지 않는다. 조건이 이미 있어도
	 * 대상이다 — 기존 값은 필드를 유형에 그대로 꽂아 만든 것이라 같은 조건이 두 번 들어가고
	 * "기타" 가 조건 행이 돼 있었다. 소득·성적 요건도 33건 중 3건·7건뿐이었다.
	 */
	@Query("""
			select r from RawScholarship r
			 where r.source = 'KOSAF_SCHOLARSHIP'
			   and r.scholarship is not null
			   and r.scholarship.applicationEndAt >= current_timestamp
			 order by r.scholarship.applicationEndAt asc
			""")
	List<RawScholarship> findOpenPublicDataTargets(Pageable pageable);

	/** 재파싱 대상: 상태와 무관하게 대학 공고 전체. 잘못 파싱된 기존 데이터를 덮어쓸 때 쓴다. */
	List<RawScholarship> findBySourceStartingWithOrderByIdAsc(String sourcePrefix, Pageable pageable);

	/**
	 * 재파싱 대상 중 <b>이 프롬프트로는 아직 안 돌린 것</b>.
	 *
	 * <p>이게 없으면 재파싱이 매번 id 오름차순 첫 100건을 다시 집는다. 실제로 배치를 세 번 돌렸는데
	 * 320회를 호출하고 처리한 공지는 100건이었다(같은 것을 3~5번씩). 프롬프트를 고쳤을 때만
	 * 다시 돌아야 하므로 버전으로 거른다.
	 *
	 * <p>본문을 못 뽑은 것(SKIPPED·IMAGE_ONLY)은 아예 뺀다. 프롬프트를 올려도 없던 본문이
	 * 생기지는 않는데, 매번 대상으로 뽑혀 자리만 차지한다 — 124건이 그랬다. 나중에 OCR·첨부
	 * 파싱으로 본문이 생기면 그때 PENDING 으로 되돌려 다시 태운다.
	 */
	@Query("""
			select r from RawScholarship r
			 where r.source like concat(:sourcePrefix, '%')
			   and r.parseStatus not in (
			       com.wishconnect.domain.scholarship.entity.ParseStatus.SKIPPED,
			       com.wishconnect.domain.scholarship.entity.ParseStatus.IMAGE_ONLY)
			   and not exists (
			       select 1 from NoticeParseLog l
			        where l.rawScholarshipId = r.id and l.promptVersion = :promptVersion)
			 order by r.id asc
			""")
	List<RawScholarship> findReparseTargets(@Param("sourcePrefix") String sourcePrefix,
			@Param("promptVersion") String promptVersion, Pageable pageable);

	/**
	 * 재파싱 대상 중 <b>아직 제대로 정제되지 않은 것</b>만.
	 *
	 * <p>이미 제목과 마감일이 제대로 들어간 공고를 다시 LLM 에 태우는 건 돈만 쓰고 얻는 게 없다.
	 * 결과가 좋아질 여지가 있는 것부터 처리한다.
	 *
	 * <p>"제대로" 의 기준은 셋이다 — 마감일이 있고, 제목이 지어낸 이름이 아니고, 공지 종류가
	 * 매겨져 있을 것. 종류가 없다는 건 그 기능이 생기기 전에 파싱됐다는 뜻이라, 제목·마감일이
	 * 멀쩡해도 다시 봐야 한다. 안 그러면 연락처 변경 안내(GUIDE)가 분류되지 않은 채 목록에 남는다.
	 * {@code "UNIV_KONKUK 공고 1200120"} 은 LLM 도 게시판도 제목을 못 줬을 때 쓰는 마지막 수단이라,
	 * 그게 남아 있다는 건 아직 정제가 안 됐다는 뜻이다.
	 */
	@Query("""
			select r from RawScholarship r
			 where r.source like concat(:sourcePrefix, '%')
			   and r.parseStatus not in (
			       com.wishconnect.domain.scholarship.entity.ParseStatus.SKIPPED,
			       com.wishconnect.domain.scholarship.entity.ParseStatus.IMAGE_ONLY)
			   and not exists (
			       select 1 from NoticeParseLog l
			        where l.rawScholarshipId = r.id and l.promptVersion = :promptVersion)
			   and (r.scholarship is null
			        or r.scholarship.applicationEndAt is null
			        or r.scholarship.noticeKind is null
			        or r.scholarship.title like concat(r.source, ' 공고 %'))
			 order by r.id asc
			""")
	List<RawScholarship> findIncompleteReparseTargets(@Param("sourcePrefix") String sourcePrefix,
			@Param("promptVersion") String promptVersion, Pageable pageable);

	long countBySourceStartingWithAndParseStatus(String sourcePrefix, ParseStatus parseStatus);
}