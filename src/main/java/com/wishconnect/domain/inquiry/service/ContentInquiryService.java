package com.wishconnect.domain.inquiry.service;

import com.wishconnect.domain.inquiry.dto.ContentInquiryRequest;
import com.wishconnect.domain.inquiry.dto.ContentInquiryResolveRequest;
import com.wishconnect.domain.inquiry.dto.ContentInquiryResponse;
import com.wishconnect.domain.inquiry.entity.ContentInquiry;
import com.wishconnect.domain.inquiry.entity.ContentInquiryStatus;
import com.wishconnect.domain.inquiry.repository.ContentInquiryRepository;
import com.wishconnect.domain.inquiry.service.InquiryAttachmentStorageService.StoredAttachment;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class ContentInquiryService {

	private final ContentInquiryRepository contentInquiryRepository;
	private final InquiryAttachmentStorageService attachmentStorageService;

	@Transactional
	public ContentInquiryResponse create(ContentInquiryRequest request, MultipartFile attachment) {
		ContentInquiry inquiry = contentInquiryRepository.save(ContentInquiry.create(
				request.inquiryType(), normalize(request.inquiryTarget()),
				normalize(request.organizationName()), request.email().trim(),
				normalize(request.phone()), request.content().trim()));
		if (attachment != null && !attachment.isEmpty()) {
			StoredAttachment stored = attachmentStorageService.store(inquiry.getId(), attachment);
			inquiry.attach(stored.key(), stored.originalName(), stored.contentType(), stored.size());
		}
		return toResponse(inquiry);
	}

	@Transactional(readOnly = true)
	public Page<ContentInquiryResponse> findAll(ContentInquiryStatus status, Pageable pageable) {
		Page<ContentInquiry> inquiries = status == null
				? contentInquiryRepository.findAllByOrderByIdDesc(pageable)
				: contentInquiryRepository.findAllByStatusOrderByIdDesc(status, pageable);
		return inquiries.map(this::toResponse);
	}

	@Transactional
	public ContentInquiryResponse resolve(Long inquiryId, ContentInquiryResolveRequest request) {
		ContentInquiry inquiry = contentInquiryRepository.findById(inquiryId)
				.orElseThrow(() -> new CustomException(ErrorCode.CONTENT_INQUIRY_NOT_FOUND));
		inquiry.resolve(request.status(), normalize(request.adminNote()));
		return toResponse(inquiry);
	}

	private ContentInquiryResponse toResponse(ContentInquiry inquiry) {
		return ContentInquiryResponse.from(inquiry,
				attachmentStorageService.downloadUrl(inquiry.getAttachmentKey()));
	}

	private String normalize(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
