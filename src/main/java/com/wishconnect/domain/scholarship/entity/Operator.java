package com.wishconnect.domain.scholarship.entity;

/**
 * 조건 비교 연산자.
 * ⚠️ 값 확정 필요 — ERD에 값 미정의. 아래는 합리적 추정치.
 */
public enum Operator {
	EQUAL,                    // =
	NOT_EQUAL,                // !=
	GREATER_THAN,             // >
	GREATER_THAN_OR_EQUAL,    // >=
	LESS_THAN,                // <
	LESS_THAN_OR_EQUAL,       // <=
	IN,                       // 포함(목록)
	BETWEEN                   // 범위(valueInt ~ valueIntMax)
}
