package com.wishconnect.global.operation;

import com.wishconnect.global.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "admin_job_run")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminJobRun extends BaseCreatedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "job_type", nullable = false, length = 80)
	private String jobType;

	@Column(nullable = false, length = 20)
	private String trigger;

	@Column(name = "actor_id")
	private UUID actorId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AdminJobStatus status;

	@Column(name = "started_at", nullable = false)
	private LocalDateTime startedAt;

	@Column(name = "finished_at")
	private LocalDateTime finishedAt;

	@Column(length = 2000)
	private String summary;

	@Column(name = "error_message", length = 2000)
	private String errorMessage;

	@Builder
	private AdminJobRun(String jobType, String trigger, UUID actorId) {
		this.jobType = jobType;
		this.trigger = trigger;
		this.actorId = actorId;
		this.status = AdminJobStatus.RUNNING;
		this.startedAt = LocalDateTime.now();
	}

	/** 단계 하나가 통째로 실패했다. 실행은 계속되며 끝나면 부분 실패로 남는다. */
	public void warn(String warning) {
		this.status = AdminJobStatus.PARTIAL_FAILURE;
		this.errorMessage = append(this.errorMessage, warning);
	}

	/** 단계 안의 일부 항목(출처·원문·장학금)이 실패했다. 상세는 admin_job_failure 에 있다. */
	public void markPartialFailure() {
		if (this.status == AdminJobStatus.RUNNING) {
			this.status = AdminJobStatus.PARTIAL_FAILURE;
		}
	}

	public void succeed(String summary) {
		if (this.status == AdminJobStatus.RUNNING) {
			this.status = AdminJobStatus.SUCCEEDED;
		}
		this.summary = summary;
		this.finishedAt = LocalDateTime.now();
	}

	public void fail(String errorMessage) {
		this.status = AdminJobStatus.FAILED;
		this.errorMessage = errorMessage;
		this.finishedAt = LocalDateTime.now();
	}

	private String append(String current, String value) {
		String joined = current == null ? value : current + "\n" + value;
		// 컬럼이 2000자다. 단계 실패가 여러 번 붙으면 넘칠 수 있어 앞부분을 남기고 자른다.
		return joined.length() <= 2000 ? joined : joined.substring(0, 1997) + "...";
	}
}
