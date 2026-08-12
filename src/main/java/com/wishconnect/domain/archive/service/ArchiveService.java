package com.wishconnect.domain.archive.service;

import com.wishconnect.domain.application.entity.Essay;
import com.wishconnect.domain.application.repository.EssayRepository;
import com.wishconnect.domain.archive.dto.ArchiveItemResponse;
import com.wishconnect.domain.archive.dto.ArchiveResponse;
import com.wishconnect.domain.common.entity.Image;
import com.wishconnect.domain.common.repository.ImageRepository;
import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScrapRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ArchiveService {

    private final ScrapRepository scrapRepository;
    private final EssayRepository essayRepository;
    private final ScholarshipRepository scholarshipRepository;
    private final ImageRepository imageRepository;
    private final ImageStorageService imageStorageService;

    public ArchiveResponse getArchive(
            UUID userId, String status, String keyword, int page, int size
    ) {
        validateStatus(status);

        // 1. 스크랩 ∪ 작성중인 자소서의 scholarshipId 합집합
        //    (스크랩 없이도 자소서를 시작할 수 있으므로, 둘 중 하나라도 있으면 목록에 포함)
        Set<Long> allScholarshipIds = new HashSet<>();
        allScholarshipIds.addAll(scrapRepository.findScholarshipIdsByUserId(userId));
        allScholarshipIds.addAll(essayRepository.findScholarshipIdsByUserId(userId));

        if (allScholarshipIds.isEmpty()) {
            return emptyResponse(page, size);
        }

        // 2. Scholarship 페이지네이션 조회
        Pageable pageable = PageRequest.of(page - 1, size);
        List<Long> scholarshipIdList = allScholarshipIds.stream().toList();

        Page<Scholarship> scholarshipPage = (keyword == null || keyword.isBlank())
                ? scholarshipRepository.findAllByIdIn(scholarshipIdList, pageable)
                : scholarshipRepository.searchByIdInAndKeyword(scholarshipIdList, keyword, pageable);

        List<Long> pageScholarshipIds = scholarshipPage.getContent().stream()
                .map(Scholarship::getId)
                .toList();

        // 3. Essay 일괄 조회 (scholarshipId -> Essay)
        List<Essay> essays = essayRepository.findAllByUser_IdAndScholarship_IdIn(userId, pageScholarshipIds);
        Map<Long, Essay> essayByScholarshipId = essays.stream()
                .collect(Collectors.toMap(e -> e.getScholarship().getId(), e -> e));

        // 4. 진행률 일괄 조회 (N+1 방지)
        List<Long> essayIds = essays.stream().map(Essay::getId).toList();
        Map<Long, int[]> progressMap = getProgressMap(essayIds);

        // 5. 포스터 이미지 일괄 조회 (N+1 방지)
        Map<Long, String> posterMap = getPosterMap(pageScholarshipIds);

        // 6. 응답 변환
        List<ArchiveItemResponse> allItems = scholarshipPage.getContent().stream()
                .map(scholarship -> toItemResponse(
                        scholarship,
                        essayByScholarshipId.get(scholarship.getId()),
                        progressMap,
                        posterMap
                ))
                .toList();

        // 7. status 필터링 (메모리 레벨, known issue)
        List<ArchiveItemResponse> filteredItems = filterByStatus(allItems, status);

        // 8. 상태별 카운트
        ArchiveResponse.CountsDto counts = calculateCounts(allItems);

        ArchiveResponse.PaginationDto pagination = new ArchiveResponse.PaginationDto(
                page, size,
                (int) scholarshipPage.getTotalElements(),
                scholarshipPage.getTotalPages()
        );

        return new ArchiveResponse(counts, filteredItems, pagination);
    }

    private ArchiveResponse emptyResponse(int page, int size) {
        return new ArchiveResponse(
                new ArchiveResponse.CountsDto(0, 0, 0, 0),
                List.of(),
                new ArchiveResponse.PaginationDto(page, size, 0, 0)
        );
    }

    private void validateStatus(String status) {
        if (status == null || "ALL".equals(status)) {
            return;
        }
        List<String> valid = List.of("NOT_STARTED", "IN_PROGRESS", "COMPLETED");
        if (!valid.contains(status)) {
            throw new CustomException(ErrorCode.INVALID_ARCHIVE_STATUS);
        }
    }

    private Map<Long, int[]> getProgressMap(List<Long> essayIds) {
        if (essayIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = essayRepository.countProgressByEssayIds(essayIds);
        Map<Long, int[]> result = new HashMap<>();
        for (Object[] row : rows) {
            Long essayId = (Long) row[0];
            int total = ((Number) row[1]).intValue();
            int completed = ((Number) row[2]).intValue();
            result.put(essayId, new int[]{total, completed});
        }
        return result;
    }

    private Map<Long, String> getPosterMap(List<Long> scholarshipIds) {
        if (scholarshipIds.isEmpty()) {
            return Map.of();
        }
        List<Image> images = imageRepository.findAllByEntityTypeAndEntityIdIn(
                ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, scholarshipIds
        );
        Map<Long, String> result = new HashMap<>();
        for (Image image : images) {
            result.putIfAbsent(image.getEntityId(), imageStorageService.publicUrl(image.getS3Key()));
        }
        return result;
    }

    private ArchiveItemResponse toItemResponse(
            Scholarship scholarship, Essay essay, Map<Long, int[]> progressMap, Map<Long, String> posterMap
    ) {
        int dDay = calculateDDay(scholarship.getApplicationEndAt());
        String urgency = calculateUrgency(dDay);
        String applicationStatus = essay != null ? essay.getStatus().name() : "NOT_STARTED";
        ArchiveItemResponse.ProgressDto progress = calculateProgress(
                progressMap, essay != null ? essay.getId() : null
        );

        return new ArchiveItemResponse(
                scholarship.getId(),
                essay != null ? essay.getId() : null,
                scholarship.getTitle(),
                List.of(),  // TODO: ScholarshipTag 매핑 테이블 부재로 임시 처리
                scholarship.getApplicationEndAt(),
                dDay,
                urgency,
                posterMap.get(scholarship.getId()),
                applicationStatus,
                progress
        );
    }

    private int calculateDDay(LocalDateTime applicationEndAt) {
        if (applicationEndAt == null) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(LocalDate.now(), applicationEndAt.toLocalDate());
    }

    private String calculateUrgency(int dDay) {
        if (dDay < 0) return "NORMAL";
        if (dDay <= 3) return "IMMINENT";
        if (dDay <= 7) return "APPROACHING";
        return "NORMAL";
    }

    private ArchiveItemResponse.ProgressDto calculateProgress(
            Map<Long, int[]> progressMap, Long essayId
    ) {
        if (essayId == null) {
            return new ArchiveItemResponse.ProgressDto(0, 0, 0);
        }
        int[] counts = progressMap.getOrDefault(essayId, new int[]{0, 0});
        int total = counts[0];
        int completed = counts[1];
        int percentage = total == 0 ? 0 : (completed * 100 / total);
        return new ArchiveItemResponse.ProgressDto(completed, total, percentage);
    }

    private List<ArchiveItemResponse> filterByStatus(
            List<ArchiveItemResponse> items, String status
    ) {
        if (status == null || "ALL".equals(status)) {
            return items;
        }
        return items.stream()
                .filter(i -> status.equals(i.applicationStatus()))
                .toList();
    }

    private ArchiveResponse.CountsDto calculateCounts(List<ArchiveItemResponse> items) {
        long all = items.size();
        long notStarted = items.stream().filter(i -> "NOT_STARTED".equals(i.applicationStatus())).count();
        long inProgress = items.stream().filter(i -> "IN_PROGRESS".equals(i.applicationStatus())).count();
        long completed = items.stream().filter(i -> "COMPLETED".equals(i.applicationStatus())).count();
        return new ArchiveResponse.CountsDto(all, notStarted, inProgress, completed);
    }
}