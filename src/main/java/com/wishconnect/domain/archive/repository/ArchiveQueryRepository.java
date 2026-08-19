package com.wishconnect.domain.archive.repository;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArchiveQueryRepository extends JpaRepository<Scholarship, Long> {

    @Query(
            value = """
                SELECT s.id AS scholarshipId, e.id AS essayId, e.status AS essayStatus
                FROM scholarship s
                LEFT JOIN essay e ON e.scholarship_id = s.id AND e.user_id = CAST(:userId AS uuid)
                WHERE s.deleted_at IS NULL
                AND (
                    EXISTS (SELECT 1 FROM scrap sc WHERE sc.scholarship_id = s.id AND sc.user_id = CAST(:userId AS uuid))
                    OR e.status IN ('IN_PROGRESS', 'COMPLETED')
                )
                AND (CAST(:keyword AS varchar) IS NULL OR s.title LIKE CONCAT('%', CAST(:keyword AS varchar), '%'))
                AND (
                    CAST(:status AS varchar) IS NULL
                    OR (CAST(:status AS varchar) = 'NOT_STARTED' AND (e.id IS NULL OR e.status = 'NOT_STARTED'))
                    OR (e.status = CAST(:status AS varchar))
                )
                ORDER BY s.application_end_at ASC NULLS LAST, s.id ASC
                """,
            countQuery = """
                SELECT COUNT(*)
                FROM scholarship s
                LEFT JOIN essay e ON e.scholarship_id = s.id AND e.user_id = CAST(:userId AS uuid)
                WHERE s.deleted_at IS NULL
                AND (
                    EXISTS (SELECT 1 FROM scrap sc WHERE sc.scholarship_id = s.id AND sc.user_id = CAST(:userId AS uuid))
                    OR e.status IN ('IN_PROGRESS', 'COMPLETED')
                )
                AND (CAST(:keyword AS varchar) IS NULL OR s.title LIKE CONCAT('%', CAST(:keyword AS varchar), '%'))
                AND (
                    CAST(:status AS varchar) IS NULL
                    OR (CAST(:status AS varchar) = 'NOT_STARTED' AND (e.id IS NULL OR e.status = 'NOT_STARTED'))
                    OR (e.status = CAST(:status AS varchar))
                )
                """,
            nativeQuery = true
    )
    Page<ArchiveRow> findArchiveRows(
            @Param("userId") UUID userId,
            @Param("status") String status,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query(
            value = """
                SELECT
                    COUNT(*) AS allCount,
                    COUNT(*) FILTER (WHERE e.id IS NULL OR e.status = 'NOT_STARTED') AS notStartedCount,
                    COUNT(*) FILTER (WHERE e.status = 'IN_PROGRESS') AS inProgressCount,
                    COUNT(*) FILTER (WHERE e.status = 'COMPLETED') AS completedCount
                FROM scholarship s
                LEFT JOIN essay e ON e.scholarship_id = s.id AND e.user_id = CAST(:userId AS uuid)
                WHERE s.deleted_at IS NULL
                AND (
                    EXISTS (SELECT 1 FROM scrap sc WHERE sc.scholarship_id = s.id AND sc.user_id = CAST(:userId AS uuid))
                    OR e.status IN ('IN_PROGRESS', 'COMPLETED')
                )
                AND (CAST(:keyword AS varchar) IS NULL OR s.title LIKE CONCAT('%', CAST(:keyword AS varchar), '%'))
                """,
            nativeQuery = true
    )
    ArchiveCountProjection countArchive(@Param("userId") UUID userId, @Param("keyword") String keyword);
}
