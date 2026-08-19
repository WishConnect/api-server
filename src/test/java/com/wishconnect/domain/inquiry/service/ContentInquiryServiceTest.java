package com.wishconnect.domain.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.inquiry.dto.ContentInquiryRequest;
import com.wishconnect.domain.inquiry.dto.ContentInquiryResolveRequest;
import com.wishconnect.domain.inquiry.dto.ContentInquiryResponse;
import com.wishconnect.domain.inquiry.entity.ContentInquiry;
import com.wishconnect.domain.inquiry.entity.ContentInquiryStatus;
import com.wishconnect.domain.inquiry.entity.ContentInquiryType;
import com.wishconnect.domain.inquiry.repository.ContentInquiryRepository;
import com.wishconnect.domain.inquiry.service.InquiryAttachmentStorageService.StoredAttachment;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class ContentInquiryServiceTest {

	@Mock ContentInquiryRepository contentInquiryRepository;
	@Mock InquiryAttachmentStorageService attachmentStorageService;
	@InjectMocks ContentInquiryService contentInquiryService;

	@Test
	void createWithoutAttachmentSavesPendingInquiry() {
		given(contentInquiryRepository.save(any(ContentInquiry.class))).willAnswer(invocation -> {
			ContentInquiry inquiry = invocation.getArgument(0);
			setId(inquiry, 1L);
			return inquiry;
		});

		ContentInquiryResponse response = contentInquiryService.create(request(), null);

		assertThat(response.inquiryId()).isEqualTo(1L);
		assertThat(response.status()).isEqualTo(ContentInquiryStatus.PENDING);
		assertThat(response.email()).isEqualTo("owner@example.com");
		verify(attachmentStorageService, never()).store(any(), any());
	}

	@Test
	void createWithAttachmentStoresMetadata() {
		given(contentInquiryRepository.save(any(ContentInquiry.class))).willAnswer(invocation -> {
			ContentInquiry inquiry = invocation.getArgument(0);
			setId(inquiry, 2L);
			return inquiry;
		});
		MockMultipartFile file = new MockMultipartFile(
				"attachment", "proof.pdf", "application/pdf", "%PDF-test".getBytes());
		given(attachmentStorageService.store(2L, file))
				.willReturn(new StoredAttachment("content-inquiries/2/file.pdf",
						"proof.pdf", "application/pdf", file.getSize()));
		given(attachmentStorageService.downloadUrl("content-inquiries/2/file.pdf"))
				.willReturn("https://signed.example/file.pdf");

		ContentInquiryResponse response = contentInquiryService.create(request(), file);

		assertThat(response.attachmentName()).isEqualTo("proof.pdf");
		assertThat(response.attachmentUrl()).isEqualTo("https://signed.example/file.pdf");
	}

	@Test
	void resolveMissingInquiryThrowsNotFound() {
		given(contentInquiryRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> contentInquiryService.resolve(99L,
				new ContentInquiryResolveRequest(ContentInquiryStatus.RESOLVED, null)))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONTENT_INQUIRY_NOT_FOUND);
	}

	private ContentInquiryRequest request() {
		return new ContentInquiryRequest(ContentInquiryType.COPYRIGHT_INFRINGEMENT,
				"OO 장학금 포스터", "OO재단", " owner@example.com ",
				"010-1234-5678", " 게시 중단을 요청합니다. ");
	}

	private void setId(ContentInquiry inquiry, Long id) {
		try {
			Field field = ContentInquiry.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(inquiry, id);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
