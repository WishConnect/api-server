package com.wishconnect.domain.archive.repository;

public interface ArchiveCountProjection {
    long getAllCount();
    long getNotStartedCount();
    long getInProgressCount();
    long getCompletedCount();
}
