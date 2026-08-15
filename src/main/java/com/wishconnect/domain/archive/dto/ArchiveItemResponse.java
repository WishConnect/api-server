package com.wishconnect.domain.archive.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ArchiveItemResponse(
        Long scholarshipId,
        Long applicationId,
        String title,
        List<String> tags,
        LocalDateTime deadline,
        int dDay,
        String urgency,
        String posterUrl,
        String applicationStatus,
        ProgressDto progress
) {
    public record ProgressDto(
            int completedQuestions,
            int totalQuestions,
            int percentage
    ) {
    }
}
