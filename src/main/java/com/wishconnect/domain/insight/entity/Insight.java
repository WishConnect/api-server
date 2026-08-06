package com.wishconnect.domain.insight.entity;

import com.wishconnect.global.common.BaseEntity;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "insight")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Insight extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "category_id")
	private InsightCategory category;

	@Column(nullable = false)
	private String title;

	@Column(columnDefinition = "TEXT")
	private String content;

	@Column(length = 1000)
	private String originalUrl;

	@Column(length = 1000)
	private String thumbnailUrl;

	@Column(nullable = false)
	private int viewCount;

	@Enumerated(EnumType.STRING)
	@Column(name = "source", length = 20)
	private InsightSource source;

	@Column
	private LocalDateTime publishedAt;
}
