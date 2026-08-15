package com.wishconnect.domain.archive.dto;

import java.util.List;

public record ArchiveResponse(
        CountsDto counts,
        List<ArchiveItemResponse> items,
        PaginationDto pagination
) {
    public record CountsDto(
            long all,
            long notStarted,
            long inProgress,
            long completed
    ) {
    }

    public record PaginationDto(
            int page,
            int size,
            int totalCount,
            int totalPages
    ) {
    }
}
