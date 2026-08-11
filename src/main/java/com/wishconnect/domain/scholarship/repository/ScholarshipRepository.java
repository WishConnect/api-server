package com.wishconnect.domain.scholarship.repository;

import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/*
정제된 장학금 데이터(scholarship)를 저장하고 조회하는 Repository입니다.
raw_scholarship 파싱 단계가 붙으면 최종 서비스용 장학금 데이터를 이 Repository로 관리합니다.
 */
public interface ScholarshipRepository extends JpaRepository<Scholarship, Long> {

	Optional<Scholarship> findByDedupKey(String dedupKey);

	// 키워드 없을 때
	@Query("SELECT s FROM Scholarship s " +
			"WHERE s.active = true " +
			"AND s.deletedAt IS NULL " +
			"AND (:category IS NULL OR s.scholarshipType = :category)")
	Page<Scholarship> findAllWithoutKeyword(
			@Param("category") ScholarshipType category,
			Pageable pageable
	);

	// 키워드 있을 때
	@Query("SELECT s FROM Scholarship s " +
			"WHERE s.active = true " +
			"AND s.deletedAt IS NULL " +
			"AND (s.title LIKE CONCAT('%', :keyword, '%') " +
			"     OR s.provider LIKE CONCAT('%', :keyword, '%')) " +
			"AND (:category IS NULL OR s.scholarshipType = :category)")
	Page<Scholarship> searchByKeyword(
			@Param("keyword") String keyword,
			@Param("category") ScholarshipType category,
			Pageable pageable
	);

	// 스크랩 필터 + 키워드 없을 때
	@Query("SELECT s FROM Scrap sc JOIN sc.scholarship s " +
			"WHERE sc.user.id = :userId " +
			"AND s.active = true AND s.deletedAt IS NULL " +
			"AND (:category IS NULL OR s.scholarshipType = :category)")
	Page<Scholarship> findScrappedByUser(
			@Param("userId") UUID userId,
			@Param("category") ScholarshipType category,
			Pageable pageable
	);

	// 스크랩 필터 + 키워드 있을 때
	@Query("SELECT s FROM Scrap sc JOIN sc.scholarship s " +
			"WHERE sc.user.id = :userId " +
			"AND s.active = true AND s.deletedAt IS NULL " +
			"AND (s.title LIKE CONCAT('%', :keyword, '%') " +
			"     OR s.provider LIKE CONCAT('%', :keyword, '%')) " +
			"AND (:category IS NULL OR s.scholarshipType = :category)")
	Page<Scholarship> searchScrappedByUserAndKeyword(
			@Param("userId") UUID userId,
			@Param("keyword") String keyword,
			@Param("category") ScholarshipType category,
			Pageable pageable
	);

	/** 추천/큐레이팅 대상: 특정 모집 상태의 활성(삭제 안 된) 장학금 전체. */
	List<Scholarship> findAllByRecruitmentStatusAndActiveTrueAndDeletedAtIsNull(RecruitmentStatus recruitmentStatus);

	/**
	 * 추천/큐레이팅 대상 + 마감 가드: 마감일이 지난 공고는 상태값이 갱신되기 전이라도 제외한다.
	 * (피드에서 사라져 sync가 재방문하지 못한 좀비 OPEN 공고 방어)
	 */
	@Query("""
			select s from Scholarship s
			where s.recruitmentStatus = :status
			  and s.active = true
			  and s.deletedAt is null
			  and (s.applicationEndAt is null or s.applicationEndAt >= :now)
			""")
	List<Scholarship> findAllOpenForRecommendation(@Param("status") RecruitmentStatus status,
												   @Param("now") LocalDateTime now);

	/** 알림 배치용: 특정 기간 안에 마감되는 활성 공고를 조회한다. */
	@Query("""
			select s from Scholarship s
			where s.recruitmentStatus = com.wishconnect.domain.scholarship.entity.RecruitmentStatus.OPEN
			  and s.active = true
			  and s.deletedAt is null
			  and s.applicationEndAt >= :start
			  and s.applicationEndAt < :end
			""")
	List<Scholarship> findOpenByApplicationEndAtBetween(@Param("start") LocalDateTime start,
														@Param("end") LocalDateTime end);

	/** 배치용: 마감일이 지났는데 CLOSED가 아닌 공고를 일괄 마감 처리한다. 처리 건수 반환. */
	@Modifying(clearAutomatically = true)
	@Query("""
			update Scholarship s
			set s.recruitmentStatus = com.wishconnect.domain.scholarship.entity.RecruitmentStatus.CLOSED,
			    s.active = false
			where s.recruitmentStatus <> com.wishconnect.domain.scholarship.entity.RecruitmentStatus.CLOSED
			  and s.applicationEndAt is not null
			  and s.applicationEndAt < :now
			""")
	int closeExpired(@Param("now") LocalDateTime now);

	/**
	 * 홈 달력용: 모집 시작일 <b>또는</b> 마감일이 해당 기간에 걸리는 공고.
	 *
	 * <p>추천용 조회({@link #findAllOpenForRecommendation})와 달리 OPEN 으로 좁히지 않는다.
	 * 달력은 "곧 모집이 시작되는 공고"도 보여줘야 하는데 그건 아직 UPCOMING 이기 때문이다.
	 * 마감이 지나 CLOSED 가 된 공고도 그 달 안이면 지난 일정으로 표시된다.
	 */
	@Query("""
			select s from Scholarship s
			where s.active = true
			  and s.deletedAt is null
			  and ((s.applicationStartAt >= :from and s.applicationStartAt < :to)
			    or (s.applicationEndAt >= :from and s.applicationEndAt < :to))
			""")
	List<Scholarship> findScheduledBetween(@Param("from") LocalDateTime from,
										   @Param("to") LocalDateTime to);

	// --- 관리자 화면 집계 -------------------------------------------------
	// 소프트 삭제분은 품질 지표에서 뺀다(이미 목록에서 내려간 공고라 고칠 대상이 아니다).

	/**
	 * 출처별 파싱 품질. 공공 API 와 대학 크롤링은 결함이 정반대라 출처를 나눠 봐야
	 * 어디를 고쳐야 하는지 드러난다.
	 */
	@Query("""
			select s.primarySource as source,
			       count(s) as total,
			       sum(case when s.summary is not null and s.summary <> '' then 1 else 0 end) as withSummary,
			       sum(case when s.amount is not null then 1 else 0 end) as withAmount,
			       sum(case when s.homepageUrl is not null and s.homepageUrl <> '' then 1 else 0 end) as withHomepageUrl
			from Scholarship s
			where s.deletedAt is null
			group by s.primarySource
			order by count(s) desc
			""")
	List<ScholarshipSourceAggregate> aggregateQualityBySource();

	long countByDeletedAtIsNotNull();

	long countByActiveTrueAndDeletedAtIsNull();

	long countByRecruitmentStatusAndDeletedAtIsNull(RecruitmentStatus recruitmentStatus);

	long countByCreatedAtGreaterThanEqual(LocalDateTime from);

	@Query("select max(s.lastSyncedAt) from Scholarship s")
	LocalDateTime findLastSyncedAt();

	/**
	 * 포스터가 붙은 장학금을 출처별로 센다. Image 는 엔티티 연관이 없어 조인이 안 되므로
	 * 포스터를 가진 id 집합을 넘겨 받는다. 호출 전에 비어 있지 않은지 확인할 것(IN () 은 문법 오류).
	 */
	@Query("""
			select s.primarySource as source, count(s) as total
			from Scholarship s
			where s.deletedAt is null and s.id in :ids
			group by s.primarySource
			""")
	List<Object[]> countBySourceForIds(@Param("ids") Collection<Long> ids);

	/**
	 * 엑셀 내보내기 대상. 내려간 공고(soft delete)는 고칠 대상이 아니라 뺀다.
	 * 출처끼리 모여야 팀원별로 나눠 맡기기 쉬워 출처 우선으로 정렬한다.
	 */
	@Query("""
			select s from Scholarship s
			where s.deletedAt is null
			order by s.primarySource asc, s.id asc
			""")
	List<Scholarship> findAllForExcelExport();

	/** 관리자 목록. 최근에 들어온 순서로 본다. 출처 필터는 null 이면 전체. */
	@Query("""
			select s from Scholarship s
			where (:source is null or s.primarySource = :source)
			order by s.createdAt desc, s.id desc
			""")
	List<Scholarship> findRecentForAdmin(@Param("source") String source, Pageable pageable);

}
