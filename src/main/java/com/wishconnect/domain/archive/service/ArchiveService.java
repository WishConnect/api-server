package com.wishconnect.domain.archive.service;

import com.wishconnect.domain.application.repository.EssayRepository;
import com.wishconnect.domain.archive.dto.ArchiveItemResponse;
import com.wishconnect.domain.archive.dto.ArchiveResponse;
import com.wishconnect.domain.archive.repository.ArchiveCountProjection;
import com.wishconnect.domain.archive.repository.ArchiveQueryRepository;
import com.wishconnect.domain.archive.repository.ArchiveRow;
import com.wishconnect.domain.common.entity.Image;
import com.wishconnect.domain.common.repository.ImageRepository;
import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private final ArchiveQueryRepository archiveQueryRepository;
    private final ScholarshipRepository scholarshipRepository;
    private final EssayRepository essayRepository;
    private final ImageRepository imageRepository;
    private final ImageStorageService imageStorageService;

    public ArchiveResponse getArchive(
            UUID userId, String status, String keyword, int page, int size
    ) {
        validateStatus(status);
        validatePage(page);

        String normalizedStatus = (status == null || "ALL".equals(status)) ? null : status;
        String normalizedKeyword = (keyword == null || keyword.isBlank()) ? null : keyword;

        Pageable pageable = PageRequest.of(page - 1, size);
        Page<ArchiveRow> rowPage = archiveQueryRepository.findArchiveRows(
                userId, normalizedStatus, normalizedKeyword, pageable
        );

        List<Long> scholarshipIds = rowPage.getContent().stream()
                .map(ArchiveRow::getScholarshipId)
                .toList();
        List<Long> essayIds = rowPage.getContent().stream()
                .map(ArchiveRow::getEssayId)
                .filter(Objects::nonNull)
                .toList();

        Map<Long, Scholarship> scholarshipById = scholarshipRepository.findAllById(scholarshipIds).stream()
                .collect(Collectors.toMap(Scholarship::getId, s -> s));

        Map<Long, int[]> progressMap = getProgressMap(essayIds);
        Map<Long, String> posterMap = getPosterMap(scholarshipIds);

        List<ArchiveItemResponse> items = rowPage.getContent().stream()
                .map(row -> toItemResponse(row, scholarshipById.get(row.getScholarshipId()), progressMap, posterMap))
                .toList();

        ArchiveCountProjection countProjection = archiveQueryRepository.countArchive(userId, normalizedKeyword);
        ArchiveResponse.CountsDto counts = new ArchiveResponse.CountsDto(
                countProjection.getAllCount(),
                countProjection.getNotStartedCount(),
                countProjection.getInProgressCount(),
                countProjection.getCompletedCount()
        );

        ArchiveResponse.PaginationDto pagination = new ArchiveResponse.PaginationDto(
                page, size,
                (int) rowPage.getTotalElements(),
                rowPage.getTotalPages()
        );

        return new ArchiveResponse(counts, items, pagination);
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

    private void validatePage(int page) {
        if (page < 1) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
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
            ArchiveRow row, Scholarship scholarship, Map<Long, int[]> progressMap, Map<Long, String> posterMap
    ) {
        int dDay = calculateDDay(scholarship.getApplicationEndAt());
        String urgency = calculateUrgency(dDay);
        String applicationStatus = row.getEssayStatus() != null ? row.getEssayStatus() : "NOT_STARTED";
        ArchiveItemResponse.ProgressDto progress = calculateProgress(progressMap, row.getEssayId());

        return new ArchiveItemResponse(
                scholarship.getId(),
                row.getEssayId(),
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
}