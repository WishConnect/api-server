package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.util.ConditionMatcher;
import com.wishconnect.domain.scholarship.util.ConditionMatcher.Result;
import com.wishconnect.domain.scholarship.util.MatchProfile;
import com.wishconnect.domain.user.entity.UserProfile;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * <b>나와 상관없는 공고</b>를 추천에서 걷어낸다. 자격 게이트와는 별개로 늘 적용한다.
 *
 * <p>자격 게이트({@code eligible})는 두 군데서 샌다. 조건이 우대(PREFERRED)로 저장돼 있으면
 * 걸지 않고, 통합 공고({@code combined})는 판정 자체를 건너뛴다. 둘 다 그럴 만한 이유가 있는
 * 완화지만 — 공고문에는 자격요건만큼 우대사항이 많고, 여러 장학금이 한 공고에 실리면 조건이
 * 서로 다른 장학금의 것과 섞인다 — <b>사는 곳과 다니는 학교에는 적용하면 안 된다.</b>
 * 그건 공고마다 다른 세부 요건이 아니라 <b>사람에 대한 사실</b>이라, 틀리면 사용자에게는
 * 곧바로 "왜 이게 뜨지"가 된다. 실제로 인천대에 다니지 않는 사용자에게 인천대 장학금이,
 * 서울 사는 사용자에게 울산·목포 장학금이 떴다.
 *
 * <p>판단 근거는 두 가지이고, <b>하나라도 어긋나면 막는다.</b>
 * <ol>
 *   <li>학교 — {@code scholarship.school_id} 가 프로필 학교와 다르면 막는다. 가장 확실한 신호다.</li>
 *   <li>조건 — {@code UNIVERSITY_TYPE}·{@code REGION_RESIDENCY} 조건이 불일치면 막는다.
 *       necessity·combined 와 무관하게 본다.</li>
 * </ol>
 *
 * <p><b>제목으로 추론하지 않는다.</b> 한때 지역 조건이 없는 공고는 제목에서 지역명을 찾아 견줬는데,
 * 그러면 {@code "서울장학재단 전국 대학생 장학금"} 처럼 기관 이름에 지역이 들어간 전국 공고가
 * 서울 밖 사용자에게서 사라진다. 제목은 근거가 아니라 힌트다. 근거는 본문에 있고,
 * {@code RegionConditionBackfillService} 가 본문을 읽어 <b>조건으로 저장</b>한다.
 * 이 관문은 저장된 조건만 본다 — 판단이 추론이 아니라 사실이고, 근거 문장이 남아 검증할 수 있다.
 *
 * <p><b>판정할 수 없으면 통과시킨다.</b> "관내에 주소를 두고" 처럼 어느 지역인지 알 수 없는 문구나,
 * 프로필에 학교·지역이 없는 사용자까지 막으면 자격 있는 사람을 떨어뜨린다. 모르는 것을 막는 실패는
 * 사용자가 알아챌 방법이 없어서, 잘못 보여주는 실패보다 고치기 어렵다.
 */
@Component
public class ScholarshipEligibilityGate {

	/** 추천 목록에 올려도 되는 공고인지. */
	public boolean belongsTo(Scholarship scholarship, List<ScholarshipCondition> conditions,
			MatchProfile matchProfile) {
		Decision decision = decide(scholarship, conditions, matchProfile);
		return decision.allowed();
	}

	/**
	 * 통과 여부와 <b>막은 이유</b>. 이유가 없으면 통과다.
	 *
	 * <p>이유를 함께 돌려주는 것은 운영을 위해서다. 지금까지 "왜 이게 떴는지"를 되짚을 방법이
	 * 없어 신고가 들어와도 코드를 처음부터 읽어야 했다.
	 */
	public Decision decide(Scholarship scholarship, List<ScholarshipCondition> conditions,
			MatchProfile matchProfile) {
		if (matchProfile == null || matchProfile.profile() == null) {
			return Decision.allow();
		}
		UserProfile profile = matchProfile.profile();

		String schoolBlock = blockedBySchool(scholarship, profile);
		if (schoolBlock != null) {
			return Decision.block(schoolBlock);
		}

		List<ScholarshipCondition> checked = conditions == null ? List.of() : conditions;
		for (ScholarshipCondition condition : checked) {
			ConditionType type = condition.getConditionType();
			if (type != ConditionType.UNIVERSITY_TYPE && type != ConditionType.REGION_RESIDENCY) {
				continue;
			}
			ConditionMatcher.Evaluation evaluation = ConditionMatcher.evaluate(condition, matchProfile);
			if (evaluation.result() == Result.MISMATCH) {
				return Decision.block(evaluation.description() == null
						? "조건 불일치(" + type + ")" : evaluation.description());
			}
		}
		return Decision.allow();
	}

	/**
	 * 학교가 지정된 공고는 그 학교 학생에게만 보인다.
	 *
	 * <p>{@code school_id} 가 없으면 "학교와 무관"이 아니라 "모른다"이므로 막지 않는다.
	 * 프로필에 학교가 없는 사용자도 마찬가지다 — 온보딩을 건너뛴 것과 해당 없음을 구별할 수 없다.
	 */
	private String blockedBySchool(Scholarship scholarship, UserProfile profile) {
		if (scholarship == null || scholarship.getSchool() == null || profile.getSchool() == null) {
			return null;
		}
		Long noticeSchoolId = scholarship.getSchool().getId();
		Long mySchoolId = profile.getSchool().getId();
		if (noticeSchoolId == null || mySchoolId == null || noticeSchoolId.equals(mySchoolId)) {
			return null;
		}
		return "다른 학교 공고(" + scholarship.getSchool().getName() + ")";
	}

	/** 통과 여부와 막은 이유. {@code reason} 은 통과일 때 null. */
	public record Decision(boolean allowed, String reason) {

		static Decision allow() {
			return new Decision(true, null);
		}

		static Decision block(String reason) {
			return new Decision(false, reason);
		}
	}
}
