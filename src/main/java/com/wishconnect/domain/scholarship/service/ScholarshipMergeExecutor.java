package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 중복 장학금 두 건을 하나로 합친다. 참조를 옮기는 실제 작업만 담당한다.
 *
 * <p><b>이 클래스가 놓치는 참조가 있으면 사용자 데이터가 유실된다.</b> scholarship 을 참조하는
 * 테이블은 조사 시점 기준 9개이며, 전부 {@code ON DELETE NO ACTION} 이다. 즉 참조가 남아 있으면
 * DB 가 삭제를 거부하므로, 옮기지 않은 참조는 조용히 사라지는 대신 오류로 드러난다.
 * 그래도 소프트 삭제를 쓰기 때문에 DB 가 막아주지 않는 구간이 있어, 목록을 여기 명시해 둔다.
 *
 * <p>처리 방식은 셋으로 나뉜다.
 * <ul>
 *   <li><b>재지정</b> — 사용자 데이터. scrap / essay / report / dispatch_log / recommendation
 *       / raw_scholarship / timeline</li>
 *   <li><b>삭제</b> — 파싱으로 다시 만들어지는 파생 데이터. condition / document</li>
 *   <li><b>소프트 삭제</b> — 중복 장학금 자신</li>
 * </ul>
 *
 * <p>{@code scrap} 은 재지정 전에 중복을 먼저 지운다. {@code (user_id, scholarship_id)} 에
 * 유니크 제약이 <b>없어서</b>(조사 결과 PK 뿐) 한 사용자가 양쪽을 스크랩했다면 병합 후 같은 항목이
 * 두 번 보이게 된다. DB 가 막아주지 않으므로 여기서 직접 걸러야 한다.
 *
 * <p>{@code essay} 는 중복을 지우지 않고 둘 다 남긴다. 사용자가 직접 쓴 글이므로, 한 장학금에
 * 지원서가 두 개 보이는 불편이 작성분을 잃는 것보다 낫다는 판단이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScholarshipMergeExecutor {

	private final EntityManager entityManager;

	/**
	 * {@code duplicate} 의 참조를 {@code primary} 로 옮기고 duplicate 를 소프트 삭제한다.
	 *
	 * @return 테이블별 처리 건수. 감사 로그와 어드민 응답에 남긴다
	 */
	public Map<String, Integer> merge(Scholarship primary, Scholarship duplicate) {
		if (primary.getId().equals(duplicate.getId())) {
			throw new IllegalArgumentException("같은 장학금은 병합할 수 없습니다. id=" + primary.getId());
		}
		Long to = primary.getId();
		Long from = duplicate.getId();
		Map<String, Integer> moved = new LinkedHashMap<>();

		// 1) 스크랩: 양쪽을 모두 스크랩한 사용자의 중복 행을 먼저 지운다.
		//    유니크 제약이 없어 이 단계를 빼면 목록에 같은 장학금이 두 번 보인다.
		moved.put("scrap.deletedDuplicate", entityManager.createQuery("""
				delete from Scrap s
				where s.scholarship.id = :from
				  and exists (select 1 from Scrap t
				              where t.scholarship.id = :to and t.user.id = s.user.id)
				""")
				.setParameter("from", from).setParameter("to", to).executeUpdate());
		moved.put("scrap.moved", repoint("Scrap", from, to));

		// 2) 자소서: 중복을 지우지 않고 둘 다 옮긴다(사용자 작성물 보존 우선).
		moved.put("essay.moved", repoint("Essay", from, to));

		// 3) 나머지 사용자·이력 데이터
		moved.put("report.moved", repoint("ScholarshipReport", from, to));
		moved.put("dispatchLog.moved", repoint("NotificationDispatchLog", from, to));
		moved.put("recommendation.moved", repoint("ScholarshipRecommendation", from, to));
		moved.put("timeline.moved", repoint("ScholarshipTimeline", from, to));

		// 4) 원본: 어느 공고에서 나온 정제 데이터인지 추적이 이어져야 한다.
		moved.put("rawScholarship.moved", repoint("RawScholarship", from, to));

		// 추천 노출·클릭 기록. @ManyToOne이 아니라 scholarshipId 필드만 있으므로 따로 처리.
		moved.put("event.moved", entityManager.createQuery(
						"update ScholarshipEvent e set e.scholarshipId = :to where e.scholarshipId = :from")
				.setParameter("to", to)
				.setParameter("from", from)
				.executeUpdate());

		// 5) 파생 데이터는 옮기지 않고 지운다. primary 쪽 값이 이미 있고,
		//    합치면 같은 조건·서류가 중복으로 쌓인다. 재파싱하면 다시 만들어진다.
		moved.put("condition.deleted", deleteBy("ScholarshipCondition", from));
		moved.put("document.deleted", deleteBy("ScholarshipDocument", from));

		// 6) 중복 장학금 자신을 목록에서 내린다. 행은 남겨 병합 이력을 추적할 수 있게 한다.
		duplicate.softDelete();

		// 벌크 연산은 영속성 컨텍스트를 우회하므로, 이후 조회가 옛 상태를 보지 않도록 비운다.
		entityManager.flush();
		entityManager.clear();

		log.info("[ScholarshipMerge] 병합 완료 primary={} duplicate={} {}", to, from, moved);
		return moved;
	}

	/** {@code scholarship_id} 를 from → to 로 바꾼다. */
	private int repoint(String entityName, Long from, Long to) {
		return entityManager.createQuery(
						"update " + entityName + " e set e.scholarship.id = :to where e.scholarship.id = :from")
				.setParameter("to", to)
				.setParameter("from", from)
				.executeUpdate();
	}

	private int deleteBy(String entityName, Long scholarshipId) {
		return entityManager.createQuery(
						"delete from " + entityName + " e where e.scholarship.id = :id")
				.setParameter("id", scholarshipId)
				.executeUpdate();
	}
}
