package com.wishconnect.domain.archive.repository;

public interface ArchiveRow {
    Long getScholarshipId();
    Long getEssayId();
    String getEssayStatus();
    Boolean getIsScrapped();
}
