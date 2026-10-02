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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 배치 실행 안에서 어떤 단계의 무엇이 왜 실패했는지. {@link AdminJobRun} 1 : N. */
@Getter
@Entity
@Table(name = "admin_job_failure")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminJobFailure extends BaseCreatedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "job_run_id", nullable = false)
	private Long jobRunId;

	@Column(nullable = false, length = 80)
	private String step;

	@Column(name = "target_type", nullable = false, length = 30)
	private String targetType;

	@Column(name = "target_id")
	private Long targetId;

	@Column(name = "target_label", length = 500)
	private String targetLabel;

	@Enumerated(EnumType.STRING)
	@Column(name = "failure_type", nullable = false, length = 30)
	private AdminJobFailureType failureType;

	@Column(length = 1000)
	private String reason;

	@Builder
	private AdminJobFailure(Long jobRunId, String step, String targetType, Long targetId, String targetLabel,
			AdminJobFailureType failureType, String reason) {
		this.jobRunId = jobRunId;
		this.step = step;
		this.targetType = targetType;
		this.targetId = targetId;
		this.targetLabel = targetLabel;
		this.failureType = failureType;
		this.reason = reason;
	}
}
