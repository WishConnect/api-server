package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.scholarship.dto.ScholarshipSearchResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipSummaryResponse;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScrapRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScholarshipService {

    private final ScholarshipRepository scholarshipRepository;
    private final ScrapRepository scrapRepository;

    public ScholarshipSearchResponse search(
            UUID userId, String keyword, String category,
            String sort,boolean scrappedOnly ,int page, int size
    ) {
        // 1. Sort 유효한지 검사
        List<String> validSorts = List.of("deadline", "amount","latest","relevance");
        if (!validSorts.contains(sort)) {
            throw new CustomException(ErrorCode.INVALID_SORT);
        }

        if (scrappedOnly && userId == null) {
            throw new CustomException(ErrorCode.LOGIN_REQUIRED);
        }

        // 2. 페이지네이션
        Pageable pageable = createPageable(page-1, size, sort);

        // 4. DB 조회
        Page<Scholarship> scholarshipPage;

        if (scrappedOnly) {
            // JOIN으로 한 번에 처리 (ID 목록 따로 안 뽑음)
            scholarshipPage = (keyword == null || keyword.isBlank())
                    ? scholarshipRepository.findScrappedByUser(userId, category, pageable)
                    : scholarshipRepository.searchScrappedByUserAndKeyword(userId, keyword, category, pageable);

        } else if (keyword == null || keyword.isBlank()) {
            scholarshipPage = scholarshipRepository.findAllWithoutKeyword(category, pageable);
        } else {
            scholarshipPage = scholarshipRepository.searchByKeyword(keyword, category, pageable);
        }

        // 4.유저의 스크랩 여부 확인
        Set<Long> scrappedInPage = getScrapped(userId,scholarshipPage.getContent());

        // 5.Entity -> DTO
        List<ScholarshipSummaryResponse> results = scholarshipPage.getContent()
                .stream()
                .map(s -> toSummaryResponse(s, scrappedInPage))
                .toList();

        // 6. 페이지네이션 정보 구성
        ScholarshipSearchResponse.PaginationDto pagination = new ScholarshipSearchResponse.PaginationDto(
                page,                                              // page
                size,                                              // size
                (int) scholarshipPage.getTotalElements(),          // totalCount
                scholarshipPage.getTotalPages()                    // totalPages
        );

        return new ScholarshipSearchResponse(
                keyword,                                           // keyword
                (int) scholarshipPage.getTotalElements(),          // totalCount
                results,                                           // results
                pagination                                         // pagination
        );

    }

    private Set<Long> getScrapped(UUID userId, List<Scholarship> scholarships) {
        if(userId == null || scholarships.isEmpty()){
            return Set.of(); // 빈 set
        }

        List<Long> scholarshipIds = scholarships.stream()
                .map(Scholarship::getId)
                .toList();
        return new HashSet<>(
                scrapRepository.findScrappedScholarshipIds(userId , scholarshipIds)
        );
    }

    private ScholarshipSummaryResponse toSummaryResponse(Scholarship scholarship, Set<Long> scrappedInPage) {

        int dDay = 0;
        if(scholarship.getApplicationEndAt() != null ){
            dDay = (int) ChronoUnit.DAYS.between(LocalDate.now(),  scholarship.getApplicationEndAt().toLocalDate());
        }
        // 모집상태
        String recruitStatus;
        if (scholarship.getApplicationEndAt() == null) {
            recruitStatus = "OPEN";
        } else if (scholarship.getApplicationEndAt().isBefore(LocalDateTime.now())) {
            recruitStatus = "CLOSED";
        } else {
            recruitStatus = "OPEN";
        }

        // 지원 기간 문자열
        String applicationPeriod = formatApplicationPeriod(
                scholarship.getApplicationStartAt(),
                scholarship.getApplicationEndAt()
        );

        // 금액
        String maxAmount = (scholarship.getAmount() != null)
                ? String.format("%,d원", scholarship.getAmount())
                : "미정";
        // 마감일 문자열
        String deadline = (scholarship.getApplicationEndAt() != null)
                ? scholarship.getApplicationEndAt().toLocalDate().toString()
                : "미정";

        return new ScholarshipSummaryResponse(
                scholarship.getId(),                              // scholarshipId
                scholarship.getTitle(),                           // title
                scholarship.getProvider(),                        // organization
                applicationPeriod,                                // applicationPeriod
                maxAmount,                                        // maxAmount
                deadline,                                         // deadline
                dDay,                                             // dDay
                recruitStatus,                                    // recruitStatus
                List.of(),                                        // tags (TODO: 태그 연동)
                scrappedInPage.contains(scholarship.getId())         // isScrapped
        );

    }

    private String formatApplicationPeriod(LocalDateTime start, LocalDateTime end) {
        if (start == null && end == null) return "미정";
        if (start == null) return "~ " + end.toLocalDate();
        if (end == null) return start.toLocalDate() + " ~";
        return start.toLocalDate() + " ~ " + end.toLocalDate();
    }


    private Pageable createPageable(int page, int size, String sort) {
        Sort sortOrder = switch (sort) {
            case "deadline" -> JpaSort.unsafe(Sort.Direction.ASC, "COALESCE(applicationEndAt, CAST('9999-12-31' AS timestamp))");
            case "latest" -> JpaSort.unsafe(Sort.Direction.DESC, "COALESCE(applicationStartAt, CAST('1900-01-01' AS timestamp))");
            case "amount" -> JpaSort.unsafe(Sort.Direction.DESC, "COALESCE(amount, 0)");
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
        return PageRequest.of(page, size, sortOrder);
    }


}
