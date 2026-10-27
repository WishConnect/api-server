package com.wishconnect.global.audit;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.repository.UserRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업 기록 목록에 사람이 읽을 이름표를 붙인다. 감사 로그 행에는 관리자 UUID 와 대상 ID 만 있어, 화면이
 * "a1b2c3d4 · SCHOLARSHIP #725" 처럼 보였다.
 *
 * <ul>
 *   <li>관리자: users.name(없으면 login_id). 이메일·연락처는 싣지 않는다.</li>
 *   <li>장학금: 대상 ID 와 병합·반려 요약에 적힌 장학금 ID 의 <b>지금</b> 제목과 내려짐 여부.
 *       한 페이지(최대 200행)에 나온 ID 를 모아 한 번씩만 조회한다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AdminAuditLogViewAssembler {

	static final String SCHOLARSHIP = "SCHOLARSHIP";

	/** 병합 요약 "중복 307 를 683 로 병합" (ScholarshipAdminController·일괄 병합이 남기는 형식). */
	private static final Pattern MERGED = Pattern.compile("중복 (\\d+) 를 (\\d+) 로 병합");
	/** 반려·실패 요약 "중복 후보 12 반려 (683 vs 307)", "중복 후보 12 (683 vs 307) 실패". */
	private static final Pattern REJECTED = Pattern.compile("\\((\\d+) vs (\\d+)\\)");

	private final UserRepository userRepository;
	private final ScholarshipRepository scholarshipRepository;

	@Transactional(readOnly = true)
	public List<AdminAuditLogResponse> assemble(List<AdminAuditLog> logs) {
		Map<UUID, String> names = actorNames(logs.stream().map(AdminAuditLog::getActorId).filter(Objects::nonNull)
				.collect(java.util.stream.Collectors.toSet()));
		Map<AdminAuditLog, Set<Long>> idsByLog = new LinkedHashMap<>();
		Set<Long> allIds = new LinkedHashSet<>();
		for (AdminAuditLog log : logs) {
			Set<Long> ids = scholarshipIds(log);
			idsByLog.put(log, ids);
			allIds.addAll(ids);
		}
		Map<Long, AdminAuditLogResponse.ScholarshipRef> refs = scholarshipRefs(allIds);
		return logs.stream().map(log -> {
			Map<Long, AdminAuditLogResponse.ScholarshipRef> own = new LinkedHashMap<>();
			idsByLog.get(log).forEach(id -> {
				if (refs.containsKey(id)) own.put(id, refs.get(id));
			});
			return AdminAuditLogResponse.of(log, names.get(log.getActorId()), own);
		}).toList();
	}

	/** 관리자 필터 항목. 이름이 없으면 로그인 아이디, 계정이 없어졌으면 UUID 앞 8자리. */
	public record Actor(UUID actorId, String name) {
	}

	@Transactional(readOnly = true)
	public List<Actor> actors(Collection<UUID> actorIds) {
		Map<UUID, String> names = actorNames(actorIds);
		return actorIds.stream().distinct()
				.map(id -> new Actor(id, names.getOrDefault(id, id.toString().substring(0, 8))))
				.sorted(java.util.Comparator.comparing(Actor::name))
				.toList();
	}

	/** 기록이 가리키는 장학금 ID: 대상(장학금일 때) + 병합·반려 요약에 적힌 두 장학금. */
	static Set<Long> scholarshipIds(AdminAuditLog log) {
		Set<Long> ids = new LinkedHashSet<>();
		if (SCHOLARSHIP.equals(log.getTargetType()) && log.getTargetId() != null) {
			ids.add(log.getTargetId());
		}
		String detail = log.getDetail();
		if (detail != null && (log.getAction() == AdminAction.SCHOLARSHIP_MERGE
				|| log.getAction() == AdminAction.SCHOLARSHIP_MERGE_REJECT
				|| log.getAction() == AdminAction.MERGE_CANDIDATE_FAILED)) {
			Matcher matcher = (log.getAction() == AdminAction.SCHOLARSHIP_MERGE ? MERGED : REJECTED).matcher(detail);
			if (matcher.find()) {
				ids.add(Long.valueOf(matcher.group(1)));
				ids.add(Long.valueOf(matcher.group(2)));
			}
		}
		return ids;
	}

	private Map<UUID, String> actorNames(Collection<UUID> actorIds) {
		Map<UUID, String> names = new HashMap<>();
		if (actorIds.isEmpty()) {
			return names;
		}
		for (User user : userRepository.findAllById(actorIds)) {
			String name = user.getName() != null && !user.getName().isBlank() ? user.getName() : user.getLoginId();
			if (name != null && !name.isBlank()) {
				names.put(user.getId(), name.trim());
			}
		}
		return names;
	}

	private Map<Long, AdminAuditLogResponse.ScholarshipRef> scholarshipRefs(Set<Long> ids) {
		Map<Long, AdminAuditLogResponse.ScholarshipRef> refs = new HashMap<>();
		if (ids.isEmpty()) {
			return refs;
		}
		for (Scholarship scholarship : scholarshipRepository.findAllById(ids)) {
			refs.put(scholarship.getId(),
					new AdminAuditLogResponse.ScholarshipRef(scholarship.getTitle(), scholarship.isDeleted()));
		}
		return refs;
	}
}
