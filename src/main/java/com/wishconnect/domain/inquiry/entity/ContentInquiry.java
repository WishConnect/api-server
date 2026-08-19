package com.wishconnect.domain.inquiry.entity;

import com.wishconnect.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "content_inquiry")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentInquiry extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "inquiry_type", length = 40)
	private ContentInquiryType inquiryType;

	@Column(name = "inquiry_target", length = 200)
	private String inquiryTarget;

	@Column(name = "organization_name", length = 100)
	private String organizationName;

	@Column(nullable = false, length = 254)
	private String email;

	@Column(length = 30)
	private String phone;

	@Column(nullable = false, length = 500)
	private String content;

	@Column(name = "attachment_key", length = 500)
	private String attachmentKey;

	@Column(name = "attachment_name", length = 255)
	private String attachmentName;

	@Column(name = "attachment_content_type", length = 100)
	private String attachmentContentType;

	@Column(name = "attachment_size")
	private Long attachmentSize;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ContentInquiryStatus status;

	@Column(name = "admin_note", length = 1000)
	private String adminNote;

	@Column(name = "resolved_at")
	private LocalDateTime resolvedAt;

	private ContentInquiry(ContentInquiryType inquiryType, String inquiryTarget,
			String organizationName, String email, String phone, String content) {
		this.inquiryType = inquiryType;
		this.inquiryTarget = inquiryTarget;
		this.organizationName = organizationName;
		this.email = email;
		this.phone = phone;
		this.content = content;
		this.status = ContentInquiryStatus.PENDING;
	}

	public static ContentInquiry create(ContentInquiryType inquiryType, String inquiryTarget,
			String organizationName, String email, String phone, String content) {
		return new ContentInquiry(inquiryType, inquiryTarget, organizationName, email, phone, content);
	}

	public void attach(String key, String originalName, String contentType, long size) {
		this.attachmentKey = key;
		this.attachmentName = originalName;
		this.attachmentContentType = contentType;
		this.attachmentSize = size;
	}

	public void resolve(ContentInquiryStatus status, String adminNote) {
		this.status = status;
		this.adminNote = adminNote;
		this.resolvedAt = status == ContentInquiryStatus.PENDING ? null : LocalDateTime.now();
	}
}
