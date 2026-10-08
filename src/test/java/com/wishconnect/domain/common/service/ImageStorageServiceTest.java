package com.wishconnect.domain.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.common.repository.ImageRepository;
import com.wishconnect.domain.common.entity.Image;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("관리자 이미지 등록 — 원인별 오류")
class ImageStorageServiceTest {

	@Mock private S3Client s3Client;
	@Mock private S3Presigner s3Presigner;
	@Mock private ImageRepository imageRepository;
    @Mock private jakarta.persistence.EntityManager entityManager;
	private ImageStorageService service;

	@BeforeEach
	void setUp() {
        service = new ImageStorageService(s3Client, s3Presigner, imageRepository, entityManager);
		ReflectionTestUtils.setField(service, "bucket", "test-bucket");
        given(imageRepository.findRepresentative(any(), any())).willReturn(Optional.empty());
        given(imageRepository.findRepresentativeForUpdate(any(), any())).willReturn(Optional.empty());
	}

	private ErrorCode codeOf(Runnable call) {
		try {
			call.run();
		} catch (CustomException e) {
			return e.getErrorCode();
		}
		throw new AssertionError("CustomException 이 나야 한다");
	}

	@Test
	@DisplayName("5MB 를 넘는 파일은 413 ADMIN_IMAGE_TOO_LARGE")
	void rejectsOversizedUpload() {
		MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png",
				new byte[(int) ImageStorageService.MAX_ADMIN_IMAGE_BYTES + 1]);

		assertThat(codeOf(() -> service.replaceFromUpload(file, "p", "SCHOLARSHIP", 1L)))
				.isEqualTo(ErrorCode.ADMIN_IMAGE_TOO_LARGE);
		assertThat(ErrorCode.ADMIN_IMAGE_TOO_LARGE.getStatus().value()).isEqualTo(413);
		verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
	}

	@Test
	@DisplayName("정확히 5MB 는 받는다")
	void acceptsExactlyFiveMegabytes() {
		MockMultipartFile file = new MockMultipartFile("file", "ok.jpg", "image/jpeg",
				new byte[(int) ImageStorageService.MAX_ADMIN_IMAGE_BYTES]);

		service.replaceFromUpload(file, "p", "SCHOLARSHIP", 1L);

		verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
		verify(imageRepository).save(any());
	}

	@Test
	@DisplayName("빈 파일·이미지가 아닌 형식·SVG 는 각각 다른 코드로 거부한다")
	void distinguishesEmptyAndFormat() {
		assertThat(codeOf(() -> service.replaceFromUpload(
				new MockMultipartFile("file", "e.png", "image/png", new byte[0]), "p", "SCHOLARSHIP", 1L)))
				.isEqualTo(ErrorCode.ADMIN_IMAGE_EMPTY);
		assertThat(codeOf(() -> service.replaceFromUpload(
				new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[10]), "p", "SCHOLARSHIP", 1L)))
				.isEqualTo(ErrorCode.ADMIN_IMAGE_INVALID_FORMAT);
		assertThat(codeOf(() -> service.replaceFromUpload(
				new MockMultipartFile("file", "x.svg", "image/svg+xml", new byte[10]), "p", "SCHOLARSHIP", 1L)))
				.isEqualTo(ErrorCode.ADMIN_IMAGE_INVALID_FORMAT);
	}

	@Test
	@DisplayName("S3 오류는 ADMIN_IMAGE_STORAGE_FAILED 로 원인을 보존해 던진다")
	void wrapsS3Failure() {
		SdkClientException s3Error = SdkClientException.create("connection reset");
		given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).willThrow(s3Error);

		assertThatThrownBy(() -> service.replaceFromUpload(
				new MockMultipartFile("file", "a.png", "image/png", new byte[10]), "p", "SCHOLARSHIP", 1L))
				.isInstanceOf(CustomException.class)
				.hasCause(s3Error)
				.extracting("errorCode").isEqualTo(ErrorCode.ADMIN_IMAGE_STORAGE_FAILED);
	}

	@Test
	@DisplayName("http(s) 가 아닌 주소는 내려받지 않고 거부한다")
	void rejectsNonHttpUrl() {
		assertThat(codeOf(() -> service.replaceFromUrl("javascript:alert(1)", "p", "SCHOLARSHIP", 1L, "t")))
				.isEqualTo(ErrorCode.ADMIN_IMAGE_URL_INVALID);
		assertThat(codeOf(() -> service.replaceFromUrl("file:///etc/passwd", "p", "SCHOLARSHIP", 1L, "t")))
				.isEqualTo(ErrorCode.ADMIN_IMAGE_URL_INVALID);
		assertThat(codeOf(() -> service.replaceFromUrl("not a url", "p", "SCHOLARSHIP", 1L, "t")))
				.isEqualTo(ErrorCode.ADMIN_IMAGE_URL_INVALID);
	}

	@Test
	@DisplayName("Content-Type 의 파라미터와 image/jpg 표기를 정규화한다")
	void normalizesContentType() {
		assertThat(ImageStorageService.validate(new byte[1], "image/JPG; charset=binary")).isEqualTo("image/jpeg");
		assertThat(ImageStorageService.validate(new byte[1], "image/webp")).isEqualTo("image/webp");
	}

    @Test
    @DisplayName("관리자 교체는 사용자 조회와 동일한 대표 행을 바꾼다")
    void replacesTheRepresentativeRow() {
        Image representative = Image.builder().id(3L).entityType("SCHOLARSHIP").entityId(1L)
                .s3Key("scholarships/manual/1/verified.png").build();
        given(imageRepository.findRepresentative("SCHOLARSHIP", 1L)).willReturn(Optional.of(representative));
        given(imageRepository.findRepresentativeForUpdate("SCHOLARSHIP", 1L)).willReturn(Optional.of(representative));

        service.replaceFromUpload(new MockMultipartFile("file", "correct.png", "image/png", new byte[10]),
                "scholarships/admin", "SCHOLARSHIP", 1L);

        verify(imageRepository).save(representative);
        assertThat(representative.getId()).isEqualTo(3L);
        assertThat(representative.getS3Key()).startsWith("scholarships/admin/1/");
    }

    @Test
    @DisplayName("자동 재수집은 관리자가 검수한 이미지를 내려받기 전부터 보존한다")
    void keepsManuallyManagedImage() {
        Image image = Image.builder().s3Key("scholarships/admin/1/verified.png").build();
        given(imageRepository.findRepresentative("SCHOLARSHIP", 1L)).willReturn(Optional.of(image));

        service.storeFromUrl("invalid url", "scholarship/llm", "SCHOLARSHIP", 1L, "t");

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(imageRepository, never()).save(any());
    }

    @Test
    @DisplayName("자동 재수집은 같은 행을 갱신하고 다른 객체 키로 캐시·원본 덮어쓰기를 방지한다")
    void recollectsIntoSameRowWithFreshObjectKey() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/poster", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png; charset=binary");
            exchange.sendResponseHeaders(200, 3);
            try (var output = exchange.getResponseBody()) { output.write(new byte[]{1, 2, 3}); }
        });
        server.start();
        try {
            Image image = Image.builder().id(7L).s3Key("scholarship/llm/1.png").build();
            given(imageRepository.findRepresentative("SCHOLARSHIP", 1L)).willReturn(Optional.of(image));
            given(imageRepository.findRepresentativeForUpdate("SCHOLARSHIP", 1L)).willReturn(Optional.of(image));
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/poster";
            service.storeFromUrl(url, "scholarship/llm", "SCHOLARSHIP", 1L, "t");
            String first = image.getS3Key();
            service.storeFromUrl(url, "scholarship/llm", "SCHOLARSHIP", 1L, "t");
            assertThat(image.getS3Key()).startsWith("scholarship/llm/1/").isNotEqualTo(first);
            assertThat(image.getContentType()).isEqualTo("image/png");
            assertThat(image.getSourceUrl()).isEqualTo(url);
            org.mockito.Mockito.verify(imageRepository, org.mockito.Mockito.times(2)).save(image);
        } finally {
            server.stop(0);
        }
    }
}
