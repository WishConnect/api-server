package com.wishconnect.domain.common.service;

import com.wishconnect.domain.common.entity.Image;
import com.wishconnect.domain.common.repository.ImageRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import org.springframework.web.multipart.MultipartFile;

/*
외부 URL의 이미지를 내려받아 S3(wishconnect-images)에 저장하고 image 테이블에 메타를 남깁니다.
- 수집 파이프라인에서 사용: 포스터가 없거나 다운로드/업로드 실패 시 null 반환(수집 자체는 계속)
- 조회 URL은 presigned URL(1시간 유효) — 버킷 퍼블릭 정책 불필요
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageStorageService {

	public static final String ENTITY_TYPE_SCHOLARSHIP = "SCHOLARSHIP";

	private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024;
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private final S3Client s3Client;
	private final S3Presigner s3Presigner;
	private final ImageRepository imageRepository;

	@Value("${app.s3.bucket:wishconnect-images}")
	private String bucket;

	@Value("${app.s3.region:ap-northeast-2}")
	private String region;

	/**
	 * 외부 이미지 URL을 S3에 저장하고 image 행을 남긴다.
	 *
	 * @return 공개 URL. 실패 시 null(호출측 흐름은 계속).
	 */
	public String storeFromUrl(String imageUrl, String keyPrefix, String entityType, Long entityId,
			String originalName) {
		try {
			HttpResponse<byte[]> response = HTTP.send(
					HttpRequest.newBuilder(URI.create(imageUrl))
							.header("User-Agent", "Mozilla/5.0 (WishConnect image collector)")
							.timeout(Duration.ofSeconds(15))
							.GET().build(),
					HttpResponse.BodyHandlers.ofByteArray());
			byte[] body = response.body();
			String contentType = response.headers().firstValue("Content-Type").orElse("");
			if (response.statusCode() != 200 || body.length == 0 || body.length > MAX_IMAGE_BYTES
					|| !contentType.startsWith("image/")) {
				log.debug("[ImageStorage] 이미지 아님/비정상 응답 url={} status={} type={}",
						imageUrl, response.statusCode(), contentType);
				return null;
			}
			String extension = extensionOf(contentType);
			String key = keyPrefix + "/" + entityId + extension;
			s3Client.putObject(PutObjectRequest.builder()
							.bucket(bucket).key(key).contentType(contentType).build(),
					RequestBody.fromBytes(body));
			imageRepository.save(Image.builder()
					.entityType(entityType)
					.entityId(entityId)
					.s3Key(key)
					.originalName(originalName)
					.contentType(contentType)
					.fileSize((long) body.length)
					.imageType("POSTER")
					.sourceUrl(imageUrl)
					.build());
			log.info("[ImageStorage] 업로드 완료 key={} size={}B", key, body.length);
			return publicUrl(key);
		} catch (Exception e) {
			log.warn("[ImageStorage] 이미지 저장 실패 url={} : {}", imageUrl, e.getMessage());
			return null;
		}
	}

	/** 관리자 교체. DB 행은 유지하고 새 S3 객체로 가리키며 기존 객체는 삭제하지 않는다. */
	public String replaceFromUrl(String imageUrl, String keyPrefix, String entityType, Long entityId,
			String originalName) {
		try {
			HttpResponse<byte[]> response = HTTP.send(
					HttpRequest.newBuilder(URI.create(imageUrl))
							.header("User-Agent", "Mozilla/5.0 (WishConnect admin image)")
							.timeout(Duration.ofSeconds(15)).GET().build(),
					HttpResponse.BodyHandlers.ofByteArray());
			String contentType = response.headers().firstValue("Content-Type").orElse("");
			if (response.statusCode() != 200 || !valid(response.body(), contentType)) return null;
			return replace(response.body(), contentType, keyPrefix, entityType, entityId, originalName, imageUrl);
		} catch (Exception e) {
			log.warn("[ImageStorage] 관리자 이미지 교체 실패 url={} : {}", imageUrl, e.getMessage());
			return null;
		}
	}

	/** 관리자가 올린 파일로 포스터를 등록·교체한다. */
	public String replaceFromUpload(MultipartFile file, String keyPrefix, String entityType,
			Long entityId) {
		try {
			String contentType = file.getContentType() == null ? "" : file.getContentType();
			byte[] body = file.getBytes();
			if (!valid(body, contentType)) return null;
			return replace(body, contentType, keyPrefix, entityType, entityId,
					file.getOriginalFilename(), null);
		} catch (Exception e) {
			log.warn("[ImageStorage] 관리자 이미지 업로드 실패 entityId={} : {}", entityId, e.getMessage());
			return null;
		}
	}

	private boolean valid(byte[] body, String contentType) {
		return body != null && body.length > 0 && body.length <= MAX_IMAGE_BYTES
				&& contentType.startsWith("image/");
	}

	private String replace(byte[] body, String contentType, String keyPrefix, String entityType,
			Long entityId, String originalName, String sourceUrl) {
		String key = keyPrefix + "/" + entityId + "/" + UUID.randomUUID() + extensionOf(contentType);
		s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
				RequestBody.fromBytes(body));
		Image image = imageRepository.findFirstByEntityTypeAndEntityIdOrderByIdDesc(entityType, entityId)
				.orElse(null);
		if (image == null) {
			image = Image.builder().entityType(entityType).entityId(entityId).s3Key(key)
					.originalName(originalName).contentType(contentType).fileSize((long) body.length)
					.imageType("POSTER").sourceUrl(sourceUrl).build();
		} else {
			image.replaceStorage(key, originalName, contentType, (long) body.length, "POSTER", sourceUrl);
		}
		imageRepository.save(image);
		return publicUrl(key);
	}

	/** 조회용 서명 URL(1시간 유효). 버킷을 공개로 열지 않아도 이미지 접근 가능. 실패 시 null. */
	public String publicUrl(String key) {
		try {
			return s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
							.signatureDuration(Duration.ofHours(1))
							.getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
							.build())
					.url().toString();
		} catch (Exception e) {
			log.warn("[ImageStorage] presign 실패 key={} : {}", key, e.getMessage());
			return null;
		}
	}

	private String extensionOf(String contentType) {
		return switch (contentType) {
			case "image/png" -> ".png";
			case "image/gif" -> ".gif";
			case "image/webp" -> ".webp";
			default -> ".jpg";
		};
	}
}
