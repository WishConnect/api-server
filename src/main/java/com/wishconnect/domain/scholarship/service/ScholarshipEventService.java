package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.scholarship.dto.ScholarshipEventRequest;
import com.wishconnect.domain.scholarship.entity.ScholarshipEvent;
import com.wishconnect.domain.scholarship.entity.ScholarshipEventType;
import com.wishconnect.domain.scholarship.repository.ScholarshipEventRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 추천 품질 측정용 행동 기록.
 *
 * <p><b>기록은 절대 본래 동작을 깨뜨리지 않는다.</b> 스크랩이 로그 저장 실패로 실패하면 사용자에게는
 * 아무 이득 없이 기능만 망가진 것이다. 그래서 별도 트랜잭션에서 저장하고, 실패하면 로그만 남긴다.
 *
 * <p>노출은 프론트가 화면 단위로 모아서 보낸다. 카드 하나마다 요청을 보내면 목록 한 번에 수십 번이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScholarshipEventService {

	private final ScholarshipEventRepository scholarshipEventRepository;

	/** 프론트가 보내는 노출·클릭. 어떤 실패도 응답을 실패로 만들지 않는다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int record(UUID userId, ScholarshipEventRequest request) {
		try {
			List<ScholarshipEvent> events = request.events().stream()
					.map(event -> ScholarshipEvent.builder()
							.userId(userId)
							.scholarshipId(event.scholarshipId())
							.eventType(event.eventType())
							.position(event.position())
							.matchScore(event.matchScore())
							.viewMode(event.viewMode())
							.build())
					.toList();
			scholarshipEventRepository.saveAll(events);
			return events.size();
		} catch (RuntimeException e) {
			log.warn("[ScholarshipEvent] 기록 실패(무시). userId={} 건수={} 사유={}",
					userId, request.events().size(), e.getMessage());
			return 0;
		}
	}

	/**
	 * 서버가 직접 아는 행동(스크랩·작성 착수).
	 *
	 * <p>프론트에 맡기지 않는다. 이건 우리 쪽에서 확실히 일어난 일이라 클라이언트가 보내주기를
	 * 기다릴 이유가 없고, 빠뜨리면 깔때기의 아래쪽이 통째로 비어 버린다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(UUID userId, Long scholarshipId, ScholarshipEventType eventType) {
		try {
			scholarshipEventRepository.save(ScholarshipEvent.builder()
					.userId(userId)
					.scholarshipId(scholarshipId)
					.eventType(eventType)
					.build());
		} catch (RuntimeException e) {
			log.warn("[ScholarshipEvent] 기록 실패(무시). type={} scholarshipId={} 사유={}",
					eventType, scholarshipId, e.getMessage());
		}
	}
}
