package com.wishconnect.domain.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.common.client.AcademicInfoApiClient;
import com.wishconnect.domain.common.client.AcademicInfoApiClient.MajorItem;
import com.wishconnect.domain.common.client.AcademicInfoApiClient.SchoolItem;
import com.wishconnect.domain.common.dto.AcademicInfoSyncResponse;
import com.wishconnect.domain.common.dto.AcademicInfoSyncStatusResponse;
import com.wishconnect.domain.common.dto.AcademicInfoSyncStatusResponse.State;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AcademicInfoSyncServiceTest {

	@Mock
	private AcademicInfoApiClient academicInfoApiClient;

	@Mock
	private AcademicInfoSyncWriteService writer;

	/** 테스트에서는 호출 스레드에서 바로 실행해 결과를 동기적으로 확인한다. */
	private final Executor directExecutor = Runnable::run;

	private AcademicInfoSyncService service;

	@BeforeEach
	void setUp() {
		service = new AcademicInfoSyncService(academicInfoApiClient, writer, directExecutor);
	}

	@Test
	@DisplayName("이미 저장된 이름과 응답 내 중복은 건너뛰고 신규만 저장한다")
	void sync_skipsExistingAndDuplicateNames() {
		given(academicInfoApiClient.fetchSchools()).willReturn(List.of(
				school("건국대학교"), school("한림대학교"), school("건국대학교")));
		given(academicInfoApiClient.fetchMajors(anyList())).willReturn(List.of(
				major("컴퓨터공학과"), major("컴퓨터공학과"), major("경영학과")));
		given(writer.findExistingSchoolNames()).willReturn(Set.of("한림대학교"));
		given(writer.findExistingMajorNames()).willReturn(Set.of());
		given(writer.saveSchoolChunk(anyList())).willAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
		given(writer.saveMajorChunk(anyList())).willAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

		AcademicInfoSyncResponse result = service.sync();

		// 학교: 건국대만 신규(한림대 기존, 건국대 중복). 전공: 컴퓨터공학과 1건 + 경영학과.
		assertThat(result.fetchedSchools()).isEqualTo(3);
		assertThat(result.savedSchools()).isEqualTo(1);
		assertThat(result.fetchedMajors()).isEqualTo(3);
		assertThat(result.savedMajors()).isEqualTo(2);
	}

	@Test
	@DisplayName("이름이 비어 있는 항목은 저장하지 않는다")
	void sync_skipsBlankNames() {
		given(academicInfoApiClient.fetchSchools()).willReturn(List.of(school("  ")));
		given(academicInfoApiClient.fetchMajors(anyList())).willReturn(List.of());
		given(writer.findExistingSchoolNames()).willReturn(Set.of());
		given(writer.findExistingMajorNames()).willReturn(Set.of());

		AcademicInfoSyncResponse result = service.sync();

		assertThat(result.savedSchools()).isZero();
		verify(writer, never()).saveSchoolChunk(anyList());
	}

	@Test
	@DisplayName("start 는 실행을 위임하고 상태를 남긴다")
	void start_recordsSucceededStatus() {
		given(academicInfoApiClient.fetchSchools()).willReturn(List.of(school("건국대학교")));
		given(academicInfoApiClient.fetchMajors(anyList())).willReturn(List.of());
		given(writer.findExistingSchoolNames()).willReturn(Set.of());
		given(writer.findExistingMajorNames()).willReturn(Set.of());
		given(writer.saveSchoolChunk(anyList())).willReturn(1);

		service.start();

		AcademicInfoSyncStatusResponse status = service.status();
		assertThat(status.state()).isEqualTo(State.SUCCEEDED);
		assertThat(status.result().savedSchools()).isEqualTo(1);
		assertThat(status.finishedAt()).isNotNull();
	}

	@Test
	@DisplayName("백그라운드 실행이 실패하면 FAILED 상태로 남고 재실행이 가능하다")
	void start_recordsFailedStatus() {
		willThrow(new IllegalStateException("공공데이터 API 오류"))
				.given(academicInfoApiClient).fetchSchools();

		service.start();

		AcademicInfoSyncStatusResponse status = service.status();
		assertThat(status.state()).isEqualTo(State.FAILED);
		assertThat(status.message()).isEqualTo("공공데이터 API 오류");

		// 실패 후 실행 플래그가 풀려 다시 시작할 수 있어야 한다.
		assertThat(service.start().state()).isEqualTo(State.FAILED);
	}

	@Test
	@DisplayName("이미 실행 중이면 새로 시작하지 않고 진행 중 상태를 반환한다")
	void start_ignoresConcurrentRequest() {
		// 동기화 도중 다시 start() 를 호출하는 상황을 재현한다.
		given(academicInfoApiClient.fetchSchools()).willAnswer(inv -> {
			AcademicInfoSyncStatusResponse duringRun = service.start();
			assertThat(duringRun.state()).isEqualTo(State.RUNNING);
			return List.<SchoolItem>of();
		});
		given(academicInfoApiClient.fetchMajors(anyList())).willReturn(List.of());
		given(writer.findExistingSchoolNames()).willReturn(Set.of());
		given(writer.findExistingMajorNames()).willReturn(Set.of());

		service.start();

		assertThat(service.status().state()).isEqualTo(State.SUCCEEDED);
		verify(academicInfoApiClient).fetchSchools();
	}

	private SchoolItem school(String name) {
		return new SchoolItem("id-" + name, name, "서울특별시", "대학교");
	}

	private MajorItem major(String name) {
		return new MajorItem(name, "공학계열");
	}
}
