package com.wishconnect.domain.scholarship.entity;

import com.wishconnect.global.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 비교 결과만 보관한다. Scholarship/Timeline에 대한 쓰기 연관관계를 두지 않는다. */
@Entity
@Getter
@Table(name = "scholarship_pipeline_run")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScholarshipPipelineRun extends BaseCreatedEntity {
    @Id
    private UUID id;
    private Long rawScholarshipId;
    private Long scholarshipId;
    @Column(nullable = false, length = 50)
    private String source;
    @Column(nullable = false, length = 40)
    private String pipelineVersion;
    @Column(length = 20)
    private String promptVersion;
    @Column(length = 60)
    private String modelId;
    @Column(nullable = false, length = 64)
    private String inputHash;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String inputJson;
    @Column(columnDefinition = "TEXT")
    private String baselineJson;
    @Column(columnDefinition = "TEXT")
    private String candidateJson;
    @Column(columnDefinition = "TEXT")
    private String responseJson;
    @Column(nullable = false, length = 25)
    private String status;
    @Column(length = 300)
    private String errorMessage;

    public ScholarshipPipelineRun(Long rawId, Long scholarshipId, String source, String version,
            String promptVersion, String modelId, String hash, String inputJson,
            String baselineJson, String candidateJson, String status, String error, String responseJson) {
        this.id = UUID.randomUUID();
        this.rawScholarshipId = rawId;
        this.scholarshipId = scholarshipId;
        this.source = source;
        this.pipelineVersion = version;
        this.promptVersion = promptVersion;
        this.modelId = modelId;
        this.inputHash = hash;
        this.inputJson = inputJson;
        this.baselineJson = baselineJson;
        this.candidateJson = candidateJson;
        this.responseJson = responseJson;
        this.status = status;
        this.errorMessage = error;
    }
}
