package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
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
 *       / raw_scholarship / event</li>
 *   <li><b>조건부 재지정</b> — 남길 쪽에 없을 때만 옮긴다. image(포스터) / interview_prep_question
 *       / timeline(남길 쪽에 있으면 중복 쪽 일정은 지운다)</li>
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
		return merge(primary, duplicate, null);
	}

	/**
	 * @param reviewer 승인한 관리자. 중복 쪽에 "병합으로 내림" 표시와 함께 남겨, 같은 공고가 다시 수집돼도
	 *                 동기화가 중복 쪽을 되살리지 않게 한다(null 이면 표시 없이 소프트 삭제만 한다)
	 */
	public Map<String, Integer> merge(Scholarship primary, Scholarship duplicate, java.util.UUID reviewer) {
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
		// 추천 노출·클릭 기록. 옮기지 않으면 사라진 장학금을 가리킨 채 남아 랭킹 학습 데이터가 샌다.
		// ScholarshipEvent 는 @ManyToOne 이 아니라 Long scholarshipId 필드만 있어 repoint() 를 쓰면 안 된다.
		// (repoint 가 만드는 e.scholarship.id 경로는 Hibernate 가 해석하지 못해 2026-08-20 이후 모든 병합 승인이
		// 500 으로 실패했다. 두 브랜치가 각자 고친 줄이 머지에서 둘 다 살아남은 사고였다.)
		moved.put("event.moved", entityManager.createQuery(
						"update ScholarshipEvent e set e.scholarshipId = :to where e.scholarshipId = :from")
				.setParameter("to", to)
				.setParameter("from", from)
				.executeUpdate());

		// 4) 원본: 어느 공고에서 나온 정제 데이터인지 추적이 이어져야 한다.
		moved.put("rawScholarship.moved", repoint("RawScholarship", from, to));

		// 5) 포스터 이미지와 면접 예상 질문. 남길 쪽에 이미 있으면 옮기지 않는다(아래 설명).
		moveImages(from, to, moved);
		moveInterviewPrepQuestions(from, to, moved);
        moveTimeline(from, to, primary, duplicate, moved);

		// 6) 파생 데이터는 옮기지 않고 지운다. primary 쪽 값이 이미 있고,
		//    합치면 같은 조건·서류가 중복으로 쌓인다. 재파싱하면 다시 만들어진다.
		//
		//    조건보다 <b>참조를 먼저</b> 지워야 한다. scholarship_condition_ref 는
		//    @ElementCollection 이고 벌크 JPQL delete 는 컬렉션 테이블을 정리해 주지 않는다.
		//    자식 행이 남은 채 부모를 지우려 하면 FK 위반으로 트랜잭션이 통째로 롤백되고,
		//    관리자 화면에서는 "병합 버튼을 눌러도 아무 일이 없는" 것처럼 보인다.
		moved.put("conditionRef.deleted", entityManager.createNativeQuery("""
				delete from scholarship_condition_ref
				where condition_id in (select id from scholarship_condition where scholarship_id = :id)
				""")
				.setParameter("id", from).executeUpdate());
		moved.put("condition.deleted", deleteBy("ScholarshipCondition", from));
		moved.put("document.deleted", deleteBy("ScholarshipDocument", from));

		// 7) 중복 장학금 자신을 목록에서 내린다. 행은 남겨 병합 이력을 추적할 수 있게 한다.
		if (reviewer != null) {
			duplicate.markMergedInto(to, reviewer);
		} else {
			duplicate.softDelete();
		}

		// 벌크 연산은 영속성 컨텍스트를 우회하므로, 이후 조회가 옛 상태를 보지 않도록 비운다.
		entityManager.flush();
		entityManager.clear();

		log.info("[ScholarshipMerge] 병합 완료 primary={} duplicate={} {}", to, from, moved);
		return moved;
	}

	/**
	 * 포스터 이미지. {@code image} 는 FK 없는 다형 연관({@code entity_type, entity_id})이라 DB 가 아무것도
	 * 막아주지 않는다.
	 *
	 * <p>남길 쪽에 이미지가 <b>없을 때만</b> 옮긴다. 사용자 화면은 장학금당 이미지 한 장(가장 최근 행)을
	 * 보여 주므로, 이미 있는데 옮겨 붙이면 관리자가 고른 포스터가 중복 쪽 포스터로 조용히 바뀔 수 있다.
	 * 옮기지 않은 이미지는 소프트 삭제된 중복 장학금에 남는다(S3 객체도 그대로라 필요하면 다시 연결할 수 있다).
	 */
	private void moveImages(Long from, Long to, Map<String, Integer> moved) {
		boolean primaryHasImage = count("select count(i) from Image i where i.entityType = :type and i.entityId = :id",
				to) > 0;
		if (primaryHasImage) {
			moved.put("image.moved", 0);
			moved.put("image.keptOnDuplicate", (int) count(
					"select count(i) from Image i where i.entityType = :type and i.entityId = :id", from));
			return;
		}
		moved.put("image.moved", entityManager.createQuery(
						"update Image i set i.entityId = :to where i.entityType = :type and i.entityId = :from")
				.setParameter("to", to)
				.setParameter("type", ImageStorageService.ENTITY_TYPE_SCHOLARSHIP)
				.setParameter("from", from)
				.executeUpdate());
		moved.put("image.keptOnDuplicate", 0);
	}

	/**
	 * 면접 예상 질문. LLM 이 장학금 단위로 만들어 둔 캐시이고 {@code UNIQUE(scholarship_id, display_order)} 가 있다.
	 *
	 * <p>남길 쪽에 질문이 <b>없을 때만</b> 통째로 옮긴다. 둘 다 있으면 순번(0, 1, 2…)이 겹쳐 유니크 위반으로
	 * 병합 전체가 롤백되고, 순번을 다시 매겨 합치면 같은 질문이 두 벌 보인다. 그래서 남길 쪽 질문을 유지하고
	 * 중복 쪽 질문은 소프트 삭제된 장학금에 그대로 둔다(지우지 않는 이유: 사용자별 예시답변
	 * {@code interview_prep_sample_answer} 가 질문을 FK 로 가리키고 있어, 지우려면 사용자 데이터까지 지워야 한다).
	 * 옮겨진 자소서의 예시답변은 남길 쪽 질문과 짝이 맞지 않으므로 화면에서 빠지고, 사용자가 다시 만들면 된다.
	 */
	private void moveInterviewPrepQuestions(Long from, Long to, Map<String, Integer> moved) {
		boolean primaryHasQuestions = countByScholarship("InterviewPrepQuestion", to) > 0;
		if (primaryHasQuestions) {
			moved.put("interviewPrep.moved", 0);
			moved.put("interviewPrep.keptOnDuplicate", (int) countByScholarship("InterviewPrepQuestion", from));
			return;
		}
		moved.put("interviewPrep.moved", repoint("InterviewPrepQuestion", from, to));
		moved.put("interviewPrep.keptOnDuplicate", 0);
	}

	/**
	 * 선발 일정. 둘 다 옮기면 같은 공고의 일정이 두 벌 쌓인다(서류 발표가 두 줄).
	 *
	 * <p>남길 쪽에 일정이 <b>1건이라도 있으면</b> 그쪽을 정본으로 보고 중복 쪽 일정은 지운다. 사람이 넣은 값이지만
	 * 남길 쪽에서 다시 확인해 고칠 수 있고, 섞어 두면 어느 줄이 맞는지 가릴 수 없다.
	 * 없으면 통째로 옮기고 순서를 0부터 다시 매긴다(옮겨 온 행의 순서가 중간부터 시작하지 않게).
	 */
    private void moveTimeline(Long from, Long to, Scholarship primary, Scholarship duplicate,
            Map<String, Integer> moved) {
        if (primary.isTimelineLocked() || countByScholarship("ScholarshipTimeline", to) > 0) {
			moved.put("timeline.moved", 0);
			moved.put("timeline.deletedOnDuplicate", deleteBy("ScholarshipTimeline", from));
			return;
		}
		moved.put("timeline.moved", repoint("ScholarshipTimeline", from, to));
        if (duplicate.isTimelineLocked()) primary.lockTimeline();
		moved.put("timeline.deletedOnDuplicate", 0);
		// 벌크 갱신으로 순서를 매긴다. 엔티티를 읽어 고치면 영속성 컨텍스트에 남은 옛 상태(scholarship_id)가
		// 함께 flush 될 수 있다.
		List<?> ids = entityManager.createQuery(
						"select t.id from ScholarshipTimeline t where t.scholarship.id = :id order by t.displayOrder, t.id")
				.setParameter("id", to)
				.getResultList();
		for (int i = 0; i < ids.size(); i++) {
			entityManager.createQuery("update ScholarshipTimeline t set t.displayOrder = :order where t.id = :id")
					.setParameter("order", i)
					.setParameter("id", ids.get(i))
					.executeUpdate();
		}
	}

	private long count(String jpql, Long entityId) {
		Object result = entityManager.createQuery(jpql)
				.setParameter("type", ImageStorageService.ENTITY_TYPE_SCHOLARSHIP)
				.setParameter("id", entityId)
				.getSingleResult();
		return result instanceof Number number ? number.longValue() : 0L;
	}

	private long countByScholarship(String entityName, Long scholarshipId) {
		Object result = entityManager.createQuery(
						"select count(e) from " + entityName + " e where e.scholarship.id = :id")
				.setParameter("id", scholarshipId)
				.getSingleResult();
		return result instanceof Number number ? number.longValue() : 0L;
	}

	/** {@code scholarship_id} 를 from → to 로 바꾼다. {@code scholarship} 연관(@ManyToOne)이 있는 엔티티에만 쓴다. */
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
