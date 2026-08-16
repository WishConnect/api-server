package com.wishconnect.domain.scholarship.entity;

import com.wishconnect.global.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
장학금 신청에 필요한 제출 서류 엔티티입니다.
자기소개서/학업계획서처럼 작성 진행률을 추적할 서류는 isEssay=true로 구분합니다.
 */
@Getter
@Entity
@Table(name = "scholarship_document")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScholarshipDocument extends BaseCreatedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "scholarship_id", nullable = false)
	private Scholarship scholarship;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(name = "is_essay", nullable = false)
	private boolean essay;

	@Column(name = "display_order", nullable = false)
	private int displayOrder;

	/**
	 * 서류 양식 다운로드 링크.
	 *
	 * <p>공공데이터 원문에는 <b>파일 URL 이 없다</b>(제출서류가 텍스트로만 온다). 그래서 이 값은
	 * 크롤링 출처의 첨부파일이나 관리자 보완 입력으로 채운다. 응답 DTO
	 * {@code RequiredDocument(name, downloadUrl)} 는 진작 있었는데 컬럼이 없어 늘 null 이었다.
	 */
	@Column(name = "download_url", length = 1000)
	private String downloadUrl;

	@Builder
	private ScholarshipDocument(
		Scholarship scholarship,
		String name,
		boolean essay,
		int displayOrder,
		String downloadUrl
	) {
		this.scholarship = scholarship;
		this.name = name;
		this.essay = essay;
		this.displayOrder = displayOrder;
		this.downloadUrl = downloadUrl;
	}

	/** 관리자 보완 입력·크롤링 첨부 연결용. */
	public void updateDownloadUrl(String downloadUrl) {
		this.downloadUrl = downloadUrl;
	}
}
