package com.wishconnect.domain.inquiry.service;

import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryAttachmentStorageService {

	private static final long MAX_BYTES = 2L * 1024 * 1024;
	private static final Map<String, String> ALLOWED_TYPES = Map.of(
			"pdf", "application/pdf",
			"png", "image/png",
			"jpg", "image/jpeg",
			"jpeg", "image/jpeg");

	private final S3Client s3Client;
	private final S3Presigner s3Presigner;

	@Value("${app.s3.bucket:wishconnect-images}")
	private String bucket;

	public StoredAttachment store(Long inquiryId, MultipartFile file) {
		validate(file);
		String extension = extension(file.getOriginalFilename());
		String contentType = ALLOWED_TYPES.get(extension);
		String key = "content-inquiries/" + inquiryId + "/" + UUID.randomUUID() + "." + extension;
		try {
			byte[] bytes = file.getBytes();
			validateSignature(extension, bytes);
			s3Client.putObject(PutObjectRequest.builder()
						.bucket(bucket).key(key).contentType(contentType).build(),
					RequestBody.fromBytes(bytes));
			return new StoredAttachment(key, file.getOriginalFilename(), contentType, bytes.length);
		} catch (CustomException e) {
			throw e;
		} catch (IOException | RuntimeException e) {
			log.error("[ContentInquiry] 첨부파일 저장 실패 inquiryId={}", inquiryId, e);
			throw new CustomException(ErrorCode.INQUIRY_ATTACHMENT_UPLOAD_FAILED);
		}
	}

	public String downloadUrl(String key) {
		if (key == null) {
			return null;
		}
		try {
			return s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
						.signatureDuration(Duration.ofMinutes(15))
						.getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
						.build()).url().toString();
		} catch (RuntimeException e) {
			log.warn("[ContentInquiry] 첨부파일 서명 URL 생성 실패 key={}", key, e);
			return null;
		}
	}

	private void validate(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			return;
		}
		if (file.getSize() > MAX_BYTES) {
			throw new CustomException(ErrorCode.INQUIRY_ATTACHMENT_TOO_LARGE);
		}
		String extension = extension(file.getOriginalFilename());
		String expectedType = ALLOWED_TYPES.get(extension);
		if (expectedType == null || !expectedType.equalsIgnoreCase(file.getContentType())) {
			throw new CustomException(ErrorCode.INQUIRY_ATTACHMENT_INVALID_FORMAT);
		}
	}

	private String extension(String name) {
		if (name == null || !name.contains(".")) {
			return "";
		}
		return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
	}

	private void validateSignature(String extension, byte[] bytes) {
		boolean valid = switch (extension) {
			case "pdf" -> startsWith(bytes, new int[]{0x25, 0x50, 0x44, 0x46, 0x2D});
			case "png" -> startsWith(bytes, new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
			case "jpg", "jpeg" -> startsWith(bytes, new int[]{0xFF, 0xD8, 0xFF});
			default -> false;
		};
		if (!valid) {
			throw new CustomException(ErrorCode.INQUIRY_ATTACHMENT_INVALID_FORMAT);
		}
	}

	private boolean startsWith(byte[] bytes, int[] signature) {
		if (bytes.length < signature.length) return false;
		for (int i = 0; i < signature.length; i++) {
			if ((bytes[i] & 0xFF) != signature[i]) return false;
		}
		return true;
	}

	public record StoredAttachment(String key, String originalName, String contentType, long size) {}
}
