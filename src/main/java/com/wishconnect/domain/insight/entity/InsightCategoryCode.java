package com.wishconnect.domain.insight.entity;

import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;

import java.util.Arrays;

public enum InsightCategoryCode {
    ACCEPTED("합격 후기"),
    SCHOLARSHIP_INFO("장학금 정보"),
    WRITING_TIP("작성 Tip"),
    EXPERIENCE("경험담");

    private final String label;

    InsightCategoryCode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static InsightCategoryCode from(String name) {
        return Arrays.stream(values())
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT));
    }
}
