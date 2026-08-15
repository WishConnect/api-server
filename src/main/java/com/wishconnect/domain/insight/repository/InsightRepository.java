package com.wishconnect.domain.insight.repository;

import java.util.List;

import com.wishconnect.domain.insight.entity.Insight;
import com.wishconnect.domain.insight.entity.InsightSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InsightRepository extends JpaRepository<Insight, Long> {

    boolean existsByOriginalUrl(String originalUrl);

    // 필터 없이 전체 조회
    @Query("SELECT i FROM Insight i " +
            "WHERE (:categoryName IS NULL OR i.category.name = :categoryName) " +
            "AND (:source IS NULL OR i.source = :source)")
    Page<Insight> findAllWithFilter(
            @Param("categoryName") String categoryName,
            @Param("source") InsightSource source,
            Pageable pageable
    );

    // 키워드 검색 포함
    @Query("SELECT i FROM Insight i " +
            "WHERE (:categoryName IS NULL OR i.category.name = :categoryName) " +
            "AND (:source IS NULL OR i.source = :source) " +
            "AND (i.title LIKE CONCAT('%', :keyword, '%') " +
            "     OR i.content LIKE CONCAT('%', :keyword, '%'))")
    Page<Insight> searchWithFilter(
            @Param("categoryName") String categoryName,
            @Param("source") InsightSource source,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    // 태그 필터 포함 (InsightTag 조인)
    @Query("SELECT i FROM InsightTag it JOIN it.insight i " +
            "WHERE it.tag.name = :tagName " +
            "AND (:categoryName IS NULL OR i.category.name = :categoryName) " +
            "AND (:source IS NULL OR i.source = :source) " +
            "ORDER BY i.publishedAt DESC")
    Page<Insight> findAllByTag(
            @Param("tagName") String tagName,
            @Param("categoryName") String categoryName,
            @Param("source") InsightSource source,
            Pageable pageable
    );

    // 특정 Insight들의 태그 목록 한 번에 조회 (N+1 방지)
    @Query("SELECT it.insight.id, it.tag.name FROM InsightTag it " +
            "WHERE it.insight.id IN :insightIds")
    List<Object[]> findTagsByInsightIds(@Param("insightIds") List<Long> insightIds);


    // 키워드 검색 포함
    @Query("SELECT i FROM Insight i " +
            "WHERE (:categoryId IS NULL OR i.category.id = :categoryId) " +
            "AND (:source IS NULL OR i.source = :source) " +
            "AND (i.title LIKE CONCAT('%', :keyword, '%') " +
            "     OR i.content LIKE CONCAT('%', :keyword, '%'))")
    Page<Insight> searchByKeyword(
            @Param("categoryId") Long categoryId,
            @Param("source") String source,
            @Param("keyword") String keyword,
            Pageable pageable
    );


    // 태그 + 키워드 둘 다 있는 경우
    @Query("SELECT i FROM InsightTag it JOIN it.insight i " +
            "WHERE it.tag.name = :tagName " +
            "AND (:categoryId IS NULL OR i.category.id = :categoryId) " +
            "AND (:source IS NULL OR i.source = :source) " +
            "AND (i.title LIKE CONCAT('%', :keyword, '%') " +
            "     OR i.content LIKE CONCAT('%', :keyword, '%'))")
    Page<Insight> searchByTagAndKeyword(
            @Param("tagName") String tagName,
            @Param("categoryId") Long categoryId,
            @Param("source") String source,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query("SELECT it.tag.name FROM InsightTag it " +
            "GROUP BY it.tag.name " +
            "ORDER BY COUNT(it) DESC")
    List<String> findPopularTagNames(Pageable pageable);


}
