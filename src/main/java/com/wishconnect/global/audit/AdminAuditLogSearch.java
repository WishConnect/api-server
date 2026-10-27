package com.wishconnect.global.audit;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * 작업 기록 목록 필터. 비어 있는 조건은 걸지 않는다.
 *
 * @param from 시작일(KST, 포함)
 * @param to   종료일(KST, 포함)
 */
public record AdminAuditLogSearch(
		AdminAction action,
		UUID actorId,
		String targetType,
		Long targetId,
		LocalDate from,
		LocalDate to
) {

	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

	/**
	 * created_at 은 서버 시계의 로컬 시각(운영 JVM 은 UTC)으로 저장된다. 화면의 날짜는 한국 날짜이므로 KST 자정을
	 * 서버 시간대로 바꿔 비교한다.
	 */
	public Specification<AdminAuditLog> toSpecification(ZoneId serverZone) {
		return (root, query, cb) -> {
			List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
			if (action != null) predicates.add(cb.equal(root.get("action"), action));
			if (actorId != null) predicates.add(cb.equal(root.get("actorId"), actorId));
			if (targetType != null && !targetType.isBlank()) predicates.add(cb.equal(root.get("targetType"), targetType.trim()));
			if (targetId != null) predicates.add(cb.equal(root.get("targetId"), targetId));
			if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), serverTime(from, serverZone)));
			if (to != null) predicates.add(cb.lessThan(root.get("createdAt"), serverTime(to.plusDays(1), serverZone)));
			return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
		};
	}

	/** KST 날짜의 0시를 서버 시간대의 LocalDateTime 으로. */
	static LocalDateTime serverTime(LocalDate koreanDate, ZoneId serverZone) {
		return koreanDate.atStartOfDay(KOREA).withZoneSameInstant(serverZone).toLocalDateTime();
	}
}
