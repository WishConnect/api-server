package com.wishconnect.domain.common.service;

import com.wishconnect.domain.common.client.AcademicInfoApiClient;
import com.wishconnect.domain.common.client.AcademicInfoApiClient.MajorItem;
import com.wishconnect.domain.common.client.AcademicInfoApiClient.SchoolItem;
import com.wishconnect.domain.common.dto.AcademicInfoSyncResponse;
import com.wishconnect.domain.common.entity.Major;
import com.wishconnect.domain.common.entity.MajorCategory;
import com.wishconnect.domain.common.entity.Region;
import com.wishconnect.domain.common.entity.School;
import com.wishconnect.domain.common.repository.MajorRepository;
import com.wishconnect.domain.common.repository.RegionRepository;
import com.wishconnect.domain.common.repository.SchoolRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/*
전국 대학/학과 공공데이터를 school, major 마스터 테이블에 저장합니다.
이미 존재하는 이름은 건너뛰어 여러 번 호출해도 중복 저장되지 않게 합니다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AcademicInfoSyncService {

	private final AcademicInfoApiClient academicInfoApiClient;
	private final SchoolRepository schoolRepository;
	private final MajorRepository majorRepository;
	private final RegionRepository regionRepository;

	@Transactional
	public AcademicInfoSyncResponse sync() {
		List<SchoolItem> schools = academicInfoApiClient.fetchSchools();
		log.info("Academic info sync fetched schools. count={}", schools.size());
		List<MajorItem> majors = academicInfoApiClient.fetchMajors(schools);
		log.info("Academic info sync fetched majors. count={}", majors.size());

		int savedSchools = saveSchools(schools);
		int savedMajors = saveMajors(majors);
		log.info("Academic info sync saved. schools={}, majors={}", savedSchools, savedMajors);
		return new AcademicInfoSyncResponse(schools.size(), savedSchools, majors.size(), savedMajors);
	}

	private int saveSchools(List<SchoolItem> items) {
		int saved = 0;
		for (SchoolItem item : items) {
			String name = normalize(item.name());
			if (!StringUtils.hasText(name) || schoolRepository.findFirstByName(name).isPresent()) {
				continue;
			}
			schoolRepository.save(School.builder()
					.name(name)
					.region(findRegion(item.regionName()))
					.schoolType(normalize(item.schoolType()))
					.build());
			saved++;
		}
		return saved;
	}

	private int saveMajors(List<MajorItem> items) {
		int saved = 0;
		for (MajorItem item : items) {
			String name = normalize(item.name());
			if (!StringUtils.hasText(name) || majorRepository.findFirstByName(name).isPresent()) {
				continue;
			}
			majorRepository.save(Major.builder()
					.name(name)
					.category(MajorCategory.from(item.category()))
					.build());
			saved++;
		}
		return saved;
	}

	private Region findRegion(String regionName) {
		String normalized = normalizeRegionName(regionName);
		if (!StringUtils.hasText(normalized)) {
			return null;
		}
		return regionRepository.findByName(normalized)
				.orElse(null);
	}

	private String normalize(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private String normalizeRegionName(String value) {
		String normalized = normalize(value);
		if (!StringUtils.hasText(normalized)) {
			return null;
		}
		return switch (normalized) {
			case "서울특별시" -> "서울";
			case "부산광역시" -> "부산";
			case "대구광역시" -> "대구";
			case "인천광역시" -> "인천";
			case "광주광역시" -> "광주";
			case "대전광역시" -> "대전";
			case "울산광역시" -> "울산";
			case "세종특별자치시" -> "세종";
			case "경기도" -> "경기";
			case "강원특별자치도", "강원도" -> "강원";
			case "충청북도" -> "충북";
			case "충청남도" -> "충남";
			case "전북특별자치도", "전라북도" -> "전북";
			case "전라남도" -> "전남";
			case "경상북도" -> "경북";
			case "경상남도" -> "경남";
			case "제주특별자치도", "제주도" -> "제주";
			default -> normalized;
		};
	}
}
