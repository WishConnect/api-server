package com.wishconnect.domain.common.service;

import com.wishconnect.domain.common.client.AcademicInfoApiClient.MajorItem;
import com.wishconnect.domain.common.client.AcademicInfoApiClient.SchoolItem;
import com.wishconnect.domain.common.entity.Major;
import com.wishconnect.domain.common.entity.MajorCategory;
import com.wishconnect.domain.common.entity.Region;
import com.wishconnect.domain.common.entity.School;
import com.wishconnect.domain.common.repository.MajorRepository;
import com.wishconnect.domain.common.repository.RegionRepository;
import com.wishconnect.domain.common.repository.SchoolRepository;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학교·전공 마스터 저장 담당. 저장 청크 하나가 트랜잭션 하나다.
 *
 * <p>{@link AcademicInfoSyncService} 와 분리한 이유는 두 가지다.
 * 하나, 외부 API 호출(수백 회, 수 분)이 트랜잭션 안에 들어가면 그동안 DB 커넥션을 붙잡아
 * HikariCP 풀(10개)을 고갈시킨다. 둘, 같은 빈 안에서 호출하면 프록시를 타지 않아
 * {@code @Transactional} 이 걸리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AcademicInfoSyncWriteService {

	private final SchoolRepository schoolRepository;
	private final MajorRepository majorRepository;
	private final RegionRepository regionRepository;

	/** 이미 저장된 학교명. 항목마다 존재 여부를 건건이 조회하지 않으려고 한 번에 읽는다. */
	@Transactional(readOnly = true)
	public Set<String> findExistingSchoolNames() {
		return Set.copyOf(schoolRepository.findAllNames());
	}

	/** 이미 저장된 전공명. 전공은 수만 건이라 건별 조회 시 그만큼 쿼리가 나간다. */
	@Transactional(readOnly = true)
	public Set<String> findExistingMajorNames() {
		return Set.copyOf(majorRepository.findAllNames());
	}

	/** 중복 판정이 끝난 신규 학교 청크를 저장한다. */
	@Transactional
	public int saveSchoolChunk(List<SchoolItem> items) {
		List<School> schools = items.stream()
				.map(item -> School.builder()
						.name(AcademicInfoNormalizer.normalize(item.name()))
						.region(findRegion(item.regionName()))
						.schoolType(AcademicInfoNormalizer.normalize(item.schoolType()))
						.build())
				.toList();
		schoolRepository.saveAll(schools);
		return schools.size();
	}

	/** 중복 판정이 끝난 신규 전공 청크를 저장한다. */
	@Transactional
	public int saveMajorChunk(List<MajorItem> items) {
		List<Major> majors = items.stream()
				.map(item -> Major.builder()
						.name(AcademicInfoNormalizer.normalize(item.name()))
						.category(MajorCategory.from(item.category()))
						.build())
				.toList();
		majorRepository.saveAll(majors);
		return majors.size();
	}

	private Region findRegion(String regionName) {
		String normalized = AcademicInfoNormalizer.toRegionName(regionName);
		if (normalized == null) {
			return null;
		}
		return regionRepository.findByName(normalized).orElse(null);
	}
}
