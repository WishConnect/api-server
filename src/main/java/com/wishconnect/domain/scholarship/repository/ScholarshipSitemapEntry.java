package com.wishconnect.domain.scholarship.repository;

import java.time.LocalDateTime;

/** sitemap.xml 용 최소 프로젝션. 엔티티를 통째로 올리면 수천 건에서 힙이 아깝다. */
public interface ScholarshipSitemapEntry {

	Long getId();

	LocalDateTime getUpdatedAt();

	/** 모집 중인가. 마감분은 사이트맵에서 우선순위·변경빈도를 낮춰 표기한다. */
	boolean getActive();
}
