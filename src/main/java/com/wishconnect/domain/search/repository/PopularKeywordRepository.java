package com.wishconnect.domain.search.repository;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PopularKeywordRepository extends JpaRepository<Scholarship, Long> {

    @Query(
            value = """
            SELECT s.provider
            FROM scholarship s
            JOIN scholarship_event se ON se.scholarship_id = s.id
            WHERE s.deleted_at IS NULL
            AND s.provider IS NOT NULL
            AND s.provider != ''
            AND s.provider !~ '^[A-Z_]+$'
            GROUP BY s.provider
            ORDER BY (
                COUNT(*) FILTER (WHERE se.event_type = 'CLICK') * 1 +
                COUNT(*) FILTER (WHERE se.event_type = 'SCRAP') * 3 +
                COUNT(*) FILTER (WHERE se.event_type = 'ESSAY_START') * 5
            ) DESC
            """,
            nativeQuery = true
    )
    List<String> findPopularProviders(Pageable pageable);
}