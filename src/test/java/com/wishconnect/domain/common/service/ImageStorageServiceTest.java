package com.wishconnect.domain.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import com.sun.net.httpserver.HttpServer;
import com.wishconnect.domain.common.entity.Image;
import com.wishconnect.domain.common.repository.ImageRepository;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@ExtendWith(MockitoExtension.class)
class ImageStorageServiceTest {

	@Mock
	private S3Client s3Client;

	@Mock
	private S3Presigner s3Presigner;

	@Mock
	private ImageRepository imageRepository;

	private HttpServer imageServer;

	@BeforeEach
	void setUp() throws IOException {
		imageServer = HttpServer.create(new InetSocketAddress(0), 0);
		imageServer.createContext("/poster.jpg", exchange -> {
			byte[] body = "fake-image-bytes".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		imageServer.start();
	}

	@AfterEach
	void tearDown() {
		if (imageServer != null) {
			imageServer.stop(0);
		}
	}

	@Test
	@DisplayName("이미지 URL을 읽어 S3에 업로드하고 image 메타데이터를 저장한다")
	void storeFromUrlUploadsToS3AndSavesImageMetadata() {
		ImageStorageService service = spy(new ImageStorageService(s3Client, s3Presigner, imageRepository));
		ReflectionTestUtils.setField(service, "bucket", "wishconnect-test");
		doReturn("https://cdn.test/scholarship/konkuk/1.jpg").when(service).publicUrl("scholarship/konkuk/1.jpg");
		given(imageRepository.save(any(Image.class))).willAnswer(invocation -> invocation.getArgument(0));

		String imageUrl = "http://localhost:" + imageServer.getAddress().getPort() + "/poster.jpg";

		String result = service.storeFromUrl(imageUrl, "scholarship/konkuk",
				ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, 1L, "poster.jpg");

		assertThat(result).isEqualTo("https://cdn.test/scholarship/konkuk/1.jpg");

		ArgumentCaptor<PutObjectRequest> putRequestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
		then(s3Client).should().putObject(putRequestCaptor.capture(), any(RequestBody.class));
		assertThat(putRequestCaptor.getValue().bucket()).isEqualTo("wishconnect-test");
		assertThat(putRequestCaptor.getValue().key()).isEqualTo("scholarship/konkuk/1.jpg");
		assertThat(putRequestCaptor.getValue().contentType()).isEqualTo("image/jpeg");

		ArgumentCaptor<Image> imageCaptor = ArgumentCaptor.forClass(Image.class);
		then(imageRepository).should().save(imageCaptor.capture());
		assertThat(imageCaptor.getValue().getEntityType()).isEqualTo(ImageStorageService.ENTITY_TYPE_SCHOLARSHIP);
		assertThat(imageCaptor.getValue().getEntityId()).isEqualTo(1L);
		assertThat(imageCaptor.getValue().getS3Key()).isEqualTo("scholarship/konkuk/1.jpg");
		assertThat(imageCaptor.getValue().getOriginalName()).isEqualTo("poster.jpg");
		assertThat(imageCaptor.getValue().getContentType()).isEqualTo("image/jpeg");
		assertThat(imageCaptor.getValue().getFileSize()).isEqualTo((long) "fake-image-bytes".length());
		assertThat(imageCaptor.getValue().getImageType()).isEqualTo("POSTER");
	}
}
