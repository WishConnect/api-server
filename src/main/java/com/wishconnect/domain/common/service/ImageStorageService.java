package com.wishconnect.domain.common.service;

import com.wishconnect.domain.common.entity.Image;
import com.wishconnect.domain.common.repository.ImageRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
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

	/** 수집 파이프라인(자동 포스터) 상한. 관리자 한도와 따로 둔다 — 수집 동작은 바꾸지 않는다. */
	private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024;
	/** 관리자 등록·교체 상한. Spring multipart max-file-size(5MB)와 같은 값이어야 한다. */
	public static final long MAX_ADMIN_IMAGE_BYTES = 5L * 1024 * 1024;
	private static final Set<String> ADMIN_IMAGE_TYPES =
			Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
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

	/**
	 * 관리자 교체(URL). DB 행은 유지하고 새 S3 객체로 가리키며 기존 객체는 삭제하지 않는다.
	 *
	 * <p>수집용 {@link #storeFromUrl} 과 달리 <b>실패를 null 로 삼키지 않는다.</b> 관리자가 무엇을 고쳐야 하는지
	 * 알 수 있게 원인별 오류 코드(크기·형식·주소·내려받기·S3)로 던진다.
	 *
	 * @throws CustomException ADMIN_IMAGE_* 중 하나
	 */
	public String replaceFromUrl(String imageUrl, String keyPrefix, String entityType, Long entityId,
			String originalName) {
		Downloaded downloaded = download(imageUrl);
		String contentType = validate(downloaded.body(), downloaded.contentType());
		return replace(downloaded.body(), contentType, keyPrefix, entityType, entityId, originalName, imageUrl.trim());
	}

	/**
	 * 관리자가 올린 파일로 포스터를 등록·교체한다.
	 *
	 * @throws CustomException ADMIN_IMAGE_* 중 하나
	 */
	public String replaceFromUpload(MultipartFile file, String keyPrefix, String entityType,
			Long entityId) {
		if (file == null || file.isEmpty()) {
			throw new CustomException(ErrorCode.ADMIN_IMAGE_EMPTY);
		}
		if (file.getSize() > MAX_ADMIN_IMAGE_BYTES) {
			throw new CustomException(ErrorCode.ADMIN_IMAGE_TOO_LARGE);
		}
		byte[] body;
		try {
			body = file.getBytes();
		} catch (IOException e) {
			throw new CustomException(ErrorCode.ADMIN_IMAGE_SAVE_FAILED, e);
		}
		String contentType = validate(body, file.getContentType());
		return replace(body, contentType, keyPrefix, entityType, entityId, file.getOriginalFilename(), null);
	}

	/**
	 * 크기·형식을 검사하고 정규화한 Content-Type 을 돌려준다.
	 *
	 * <p>SVG 는 받지 않는다. 스크립트를 담을 수 있어 사용자 화면에 그대로 내보내면 위험하다.
	 */
	static String validate(byte[] body, String contentType) {
		if (body == null || body.length == 0) {
			throw new CustomException(ErrorCode.ADMIN_IMAGE_EMPTY);
		}
		if (body.length > MAX_ADMIN_IMAGE_BYTES) {
			throw new CustomException(ErrorCode.ADMIN_IMAGE_TOO_LARGE);
		}
		String normalized = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
		if ("image/jpg".equals(normalized)) {
			normalized = "image/jpeg";
		}
		if (!ADMIN_IMAGE_TYPES.contains(normalized)) {
			throw new CustomException(ErrorCode.ADMIN_IMAGE_INVALID_FORMAT);
		}
		return normalized;
	}

	/** 관리자 이미지 URL 내려받기. 크기 상한을 넘으면 끝까지 받지 않고 끊는다. */
	private Downloaded download(String imageUrl) {
		URI uri;
		try {
			uri = URI.create(imageUrl == null ? "" : imageUrl.trim());
		} catch (IllegalArgumentException e) {
			throw new CustomException(ErrorCode.ADMIN_IMAGE_URL_INVALID);
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
			throw new CustomException(ErrorCode.ADMIN_IMAGE_URL_INVALID);
		}
		HttpResponse<InputStream> response;
		try {
			response = HTTP.send(HttpRequest.newBuilder(uri)
							.header("User-Agent", "Mozilla/5.0 (WishConnect admin image)")
							.timeout(Duration.ofSeconds(15)).GET().build(),
					HttpResponse.BodyHandlers.ofInputStream());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CustomException(ErrorCode.ADMIN_IMAGE_DOWNLOAD_FAILED, e);
		} catch (IOException | IllegalArgumentException e) {
			log.warn("[ImageStorage] 관리자 이미지 내려받기 실패 url={} : {}", imageUrl, e.toString());
			throw new CustomException(ErrorCode.ADMIN_IMAGE_DOWNLOAD_FAILED, e);
		}
		try (InputStream in = response.body()) {
			if (response.statusCode() != 200) {
				log.warn("[ImageStorage] 관리자 이미지 내려받기 비정상 응답 url={} status={}",
						imageUrl, response.statusCode());
				throw new CustomException(ErrorCode.ADMIN_IMAGE_DOWNLOAD_FAILED);
			}
			long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
			if (declared > MAX_ADMIN_IMAGE_BYTES) {
				throw new CustomException(ErrorCode.ADMIN_IMAGE_TOO_LARGE);
			}
			byte[] body = in.readNBytes((int) MAX_ADMIN_IMAGE_BYTES + 1);
			if (body.length > MAX_ADMIN_IMAGE_BYTES) {
				throw new CustomException(ErrorCode.ADMIN_IMAGE_TOO_LARGE);
			}
			return new Downloaded(body, response.headers().firstValue("Content-Type").orElse(""));
		} catch (IOException e) {
			throw new CustomException(ErrorCode.ADMIN_IMAGE_DOWNLOAD_FAILED, e);
		}
	}

	private record Downloaded(byte[] body, String contentType) {
	}

	private String replace(byte[] body, String contentType, String keyPrefix, String entityType,
			Long entityId, String originalName, String sourceUrl) {
		String key = keyPrefix + "/" + entityId + "/" + UUID.randomUUID() + extensionOf(contentType);
		try {
			s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
					RequestBody.fromBytes(body));
		} catch (SdkException e) {
			log.error("[ImageStorage] S3 저장 실패 key={} : {}", key, e.toString());
			throw new CustomException(ErrorCode.ADMIN_IMAGE_STORAGE_FAILED, e);
		}
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
