package com.wishconnect.domain.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@ExtendWith(MockitoExtension.class)
class InquiryAttachmentStorageServiceTest {

	@Mock S3Client s3Client;
	@Mock S3Presigner s3Presigner;
	private InquiryAttachmentStorageService storageService;

	@BeforeEach
	void setUp() {
		storageService = new InquiryAttachmentStorageService(s3Client, s3Presigner);
		ReflectionTestUtils.setField(storageService, "bucket", "test-bucket");
	}

	@Test
	void rejectsExtensionOutsideAllowList() {
		MockMultipartFile file = new MockMultipartFile(
				"attachment", "script.exe", "application/octet-stream", new byte[]{1, 2, 3});

		assertThatThrownBy(() -> storageService.store(1L, file))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INQUIRY_ATTACHMENT_INVALID_FORMAT);
	}

	@Test
	void rejectsFileWhoseBytesDoNotMatchExtension() {
		MockMultipartFile file = new MockMultipartFile(
				"attachment", "fake.pdf", "application/pdf", "not-pdf".getBytes());

		assertThatThrownBy(() -> storageService.store(1L, file))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INQUIRY_ATTACHMENT_INVALID_FORMAT);
	}

	@Test
	void storesValidPdf() {
		MockMultipartFile file = new MockMultipartFile(
				"attachment", "proof.pdf", "application/pdf", "%PDF-test".getBytes());

		InquiryAttachmentStorageService.StoredAttachment stored = storageService.store(3L, file);

		assertThat(stored.key()).startsWith("content-inquiries/3/").endsWith(".pdf");
		assertThat(stored.originalName()).isEqualTo("proof.pdf");
		verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
	}
}
