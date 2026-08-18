package com.wishconnect.domain.scholarship.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.scholarship.dto.ParsedNotice;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.RequirementLevel;
import com.wishconnect.domain.scholarship.entity.SubmissionChannel;
import com.wishconnect.domain.scholarship.entity.NoticeKind;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LLM 파서의 본문 추출·응답 파싱·검증 검증.
 *
 * <p>네트워크를 타지 않으므로 크레딧 없이 돌아간다. 검증 케이스는 실제 수집 데이터에서
 * 문제가 됐던 패턴(근무기간 오인, 페이지 전체 저장)을 그대로 가져왔다.
 */
class UnivNoticeLlmParserTest {

	private final UnivNoticeLlmParser parser = new UnivNoticeLlmParser(new ObjectMapper());

	private ParsedNotice notice(String start, String end, String evidence) {
		return notice(start, end, evidence, List.of());
	}

	private ParsedNotice noticeWithConditions(ParsedNotice.Condition... conditions) {
		return notice(null, null, null, List.of(conditions));
	}

	/** 필드가 20개라 위치로 쓰면 읽기도 고치기도 어렵다. 테스트가 신경 쓰는 것만 인자로 받는다. */
	private ParsedNotice notice(String start, String end, String evidence,
			List<ParsedNotice.Condition> conditions) {
		return new ParsedNotice(
				"제목", "경희대학교", "INTERNAL",   // title · provider · scholarshipType
				start, end, evidence,              // 신청기간과 그 근거
				null, null, null,                  // selectionCount · amount · summary
				null, null,                        // noticeKind · combined
				null, null, null,                  // submissionMethod · submissionChannel · 근거
				null, null, null, null,            // 자소서·면접 판단과 근거
				List.of(), conditions);            // documents · conditions
	}

	// --- v4: 제출방식 근거 · 첨부 · 새 분류 ---

	private ParsedNotice submission(String method, String channel, String evidence) {
		return new ParsedNotice(
				"제목", "경희대학교", "INTERNAL",
				null, null, null,
				null, null, null,
				null, null,
				method, channel, evidence,
				null, null, null, null,
				List.of(), List.of());
	}

	@Test
	@DisplayName("제출방식 근거가 본문에 없으면 방식·채널을 함께 버린다")
	void discardsUngroundedSubmission() {
		// 본문이 첨부뿐인 공고에 "이메일로 서류 접수" 가 지어내진 채 저장된 일이 있었다(3906).
		var result = parser.resolveSubmission(
				submission("이메일로 서류 접수", "EMAIL", "이메일로 서류 접수"),
				"글번호 260210 작성자 장학팀 첨부파일 공고문.docx", null);

		assertThat(result.method()).isNull();
		assertThat(result.channel()).isNull();
	}

	@Test
	@DisplayName("근거가 본문에 있으면 제출방식을 살린다")
	void keepsGroundedSubmission() {
		var result = parser.resolveSubmission(
				submission("이메일 제출(komin78@inu.ac.kr)", "EMAIL", "이메일로 서류제출"),
				"7. 지원방법 : 이메일(komin78@inu.ac.kr)로 서류제출", null);

		assertThat(result.channel()).isEqualTo(SubmissionChannel.EMAIL);
		assertThat(result.method()).isEqualTo("이메일 제출(komin78@inu.ac.kr)");
	}

	@Test
	@DisplayName("학교를 거쳐야 하는 공고는 THIRD_PARTY 다 — 학생이 직접 못 낸다")
	void recognizesThirdPartyChannel() {
		var result = parser.resolveSubmission(
				submission("학교장 명의 전자공문 제출", "THIRD_PARTY", "학교장 명의 전자공문으로만 신청 가능"),
				"마. 유의사항: 개인신청은 불가하며, 학교장 명의 전자공문으로만 신청 가능", null);

		assertThat(result.channel()).isEqualTo(SubmissionChannel.THIRD_PARTY);
	}

	@Test
	@DisplayName("장학금이 아닌 공고를 가려낸다")
	void recognizesNonScholarshipNotice() {
		// 성균관대 장학 게시판에 올라온 VR 실험 참여자 모집·직원 채용(4229·4230).
		assertThat(parser.resolveNoticeKind("NOT_SCHOLARSHIP")).isEqualTo(NoticeKind.NOT_SCHOLARSHIP);
	}

	@Test
	@DisplayName("첨부 파일명을 프롬프트에 함께 보낸다 — 본문이 비어도 자소서 여부가 드러난다")
	void sendsAttachmentNames() {
		String html = """
				<html><body><div class="view_cont">자세한 사항은 붙임파일 확인</div>
				<a href="/down?id=1">2026년 상반기 사랑나눔 장학생 자기소개서.docx</a>
				<a href="/down?id=2">개인정보 동의서.hwp</a>
				<a href="/list">목록</a></body></html>
				""";

		var attachments = parser.extractAttachments(html);
		assertThat(attachments)
				.containsExactly("2026년 상반기 사랑나눔 장학생 자기소개서.docx", "개인정보 동의서.hwp");

		var request = parser.buildRequest("제목", "본문", attachments);
		assertThat(request.messages().get(0).content())
				.contains("[첨부]")
				.contains("사랑나눔 장학생 자기소개서.docx");
	}

	@Test
	@DisplayName("첨부 파일명도 근거로 인정한다 — 본문이 비어 있으면 거기가 유일한 단서다")
	void acceptsAttachmentAsEvidence() {
		// 프롬프트로는 첨부를 보내면서 대조는 본문·제목만 해, 파일명을 근거로 댄 판단이 버려졌다(3970).
		var result = parser.resolveRequirement("REQUIRED",
				"붙임_2._[서식]_2026년_상반기_서울인재대학장학금_자기소개서[1].hwp",
				"선발 공고 홍보 포스터. 8월 3일부터 8월 10일까지 모집함",
				null,
				List.of("붙임_2._[서식]_2026년_상반기_서울인재대학장학금_자기소개서[1].hwp"));

		assertThat(result.level()).isEqualTo(RequirementLevel.REQUIRED);
	}

	@Test
	@DisplayName("첨부에도 없는 근거는 여전히 버린다")
	void stillDiscardsEvidenceMissingEverywhere() {
		var result = parser.resolveRequirement("REQUIRED", "자기소개서를 제출해야 합니다",
				"본문에는 그런 말이 없다", null, List.of("공고문.pdf"));

		assertThat(result.level()).isNull();
	}

	// --- 본문 추출 ---

	@Test
	@DisplayName("메뉴·푸터·스크립트를 걷어내고 본문 텍스트만 남긴다")
	void extractsBodyWithoutChrome() {
		String html = """
				<html><head><style>.a{color:red}</style><script>var x=1;</script></head>
				<body>
				  <nav>전체메뉴 학사안내 장학 도서관</nav>
				  <div id="header">경희대학교 로그인 검색</div>
				  <div class="artclView">2026학년도 2학기 운연장학 장학생을 모집합니다. 신청기간은 8월 12일부터 8월 21일까지이며, 제출서류는 자기소개서와 성적증명서입니다.</div>
				  <footer>개인정보처리방침 이메일무단수집거부 COPYRIGHT</footer>
				</body></html>
				""";

		var body = parser.extractBody(html);

		assertThat(body).isPresent();
		assertThat(body.get().text()).contains("2026학년도 2학기 운연장학 장학생을 모집합니다");
		assertThat(body.get().text()).doesNotContain("전체메뉴");
		assertThat(body.get().text()).doesNotContain("COPYRIGHT");
		assertThat(body.get().text()).doesNotContain("var x=1");
		assertThat(body.get().truncated()).isFalse();
	}

	@Test
	@DisplayName("본문이 너무 짧으면 비운다 (LLM 호출 자체를 생략하기 위함)")
	void skipsTooShortBody() {
		assertThat(parser.extractBody("<html><body>첨부파일 참고</body></html>")).isEmpty();
		assertThat(parser.extractBody("")).isEmpty();
		assertThat(parser.extractBody(null)).isEmpty();
	}

	@Test
	@DisplayName("본문이 상한을 넘으면 잘라낸다 (페이지 전체가 저장된 경우 방어)")
	void truncatesLongBody() {
		String long텍스트 = "장학금 신청 안내입니다. ".repeat(2000);   // 약 26,000자
		var body = parser.extractBody(
				"<html><body><div class='artclView'>" + long텍스트 + "</div></body></html>");

		assertThat(body).isPresent();
		assertThat(body.get().text().length()).isEqualTo(6_000);
		assertThat(body.get().truncated()).isTrue();
		assertThat(body.get().originalLength()).isGreaterThan(6_000);
	}

	// --- 응답 파싱 ---

	@Test
	@DisplayName("코드펜스로 감싸 와도 파싱한다")
	void readsFencedJson() {
		String response = """
				```json
				{"title":"운연장학","provider":"경희대학교","scholarshipType":"INTERNAL",
				 "applicationStart":"2026-08-01","applicationEnd":"2026-08-14",
				 "periodEvidence":"신청기간 : 2026. 8. 1. ~ 8. 14.",
				 "selectionCount":10,"amount":1000000,"summary":"요약","documents":["자기소개서"]}
				```
				""";

		var parsed = parser.readResponse(response);

		assertThat(parsed).isPresent();
		assertThat(parsed.get().title()).isEqualTo("운연장학");
		assertThat(parsed.get().safeDocuments()).containsExactly("자기소개서");
	}

	@Test
	@DisplayName("JSON 앞뒤에 잡담이 붙어도 객체만 뽑아낸다")
	void readsJsonWithSurroundingText() {
		String response = "다음과 같이 추출했습니다.\n{\"title\":\"장학\",\"documents\":[]}\n확인해주세요.";

		assertThat(parser.readResponse(response)).isPresent();
		assertThat(parser.readResponse(response).get().title()).isEqualTo("장학");
	}

	@Test
	@DisplayName("JSON 이 아니면 비운다")
	void returnsEmptyOnNonJson() {
		assertThat(parser.readResponse("추출할 정보가 없습니다.")).isEmpty();
		assertThat(parser.readResponse("")).isEmpty();
		assertThat(parser.readResponse(null)).isEmpty();
	}

	// --- 환각 방어 (가장 중요) ---

	@Test
	@DisplayName("근거 문장이 본문에 있으면 기간을 인정한다")
	void acceptsPeriodWithGroundedEvidence() {
		String body = "2026학년도 2학기 운연장학 안내. 신청기간 : 2026. 8. 1. ~ 8. 14. 제출서류는 자기소개서.";

		var period = parser.resolvePeriod(
				notice("2026-08-01", "2026-08-14", "신청기간 : 2026. 8. 1. ~ 8. 14."), body);

		assertThat(period).isPresent();
		assertThat(period.get().start().toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 1));
		assertThat(period.get().end().toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 14));
	}

	@Test
	@DisplayName("연도 없는 표기도 근거로 인정한다 — '(~8/13)' 하나로 여덟 건을 버렸다")
	void acceptsEvidenceWithoutYear() {
		String body = "2026-2학기 교내장학 안내입니다. 신청은 학사시스템에서 하시기 바랍니다.(~8/13)";

		var period = parser.resolvePeriod(
				notice(null, "2026-08-13", "(~8/13)"), body, LocalDate.of(2026, 8, 5));

		assertThat(period).isPresent();
		assertThat(period.get().end().toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 13));
	}

	@Test
	@DisplayName("줄여 쓴 연도도 인정한다 — \"'26. 8. 12.(수)~9. 9.(수)\"")
	void acceptsEvidenceWithShortYear() {
		String body = "국가근로장학금 2차 신청기간 : '26. 8. 12.(수) 9시 ~ 9. 9.(수) 18시";

		var period = parser.resolvePeriod(
				notice(null, "2026-09-09", "'26. 8. 12.(수) 9시~9. 9.(수) 18시"),
				body, LocalDate.of(2026, 8, 12));

		assertThat(period).isPresent();
		assertThat(period.get().end().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 9));
	}

	@Test
	@DisplayName("연도는 모델이 아니라 수집 시점으로 정한다 — 제목의 '(~9/18' 을 2024 년으로 읽은 적이 있다")
	void takesYearFromReferenceDateNotFromModel() {
		String body = "앨트웰민초장학재단 제27기 장학생 선발 안내(~9/18 금 18시, 1학년 대상)";

		var period = parser.resolvePeriod(
				notice(null, "2024-09-18", "(~9/18 금 18시"), body, LocalDate.of(2026, 8, 14));

		assertThat(period).isPresent();
		assertThat(period.get().end().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 18));
	}

	@Test
	@DisplayName("연말 공고의 연도 없는 마감일은 다음 해로 넘긴다")
	void rollsYearForwardForYearEndNotice() {
		String body = "겨울 계절학기 장학 신청은 1/15 까지입니다.";

		var period = parser.resolvePeriod(
				notice(null, "2026-01-15", "1/15 까지"), body, LocalDate.of(2026, 12, 20));

		assertThat(period).isPresent();
		assertThat(period.get().end().toLocalDate()).isEqualTo(LocalDate.of(2027, 1, 15));
	}

	@Test
	@DisplayName("근거에 없는 날을 마감일로 넣으면 버린다 — 본문은 6/29 인데 6/22 를 넣은 사례")
	void rejectsDeadlineNotQuotedInEvidence() {
		String body = "국가근로장학금 1차 신청 : 6. 29.(월) 18시까지";

		var period = parser.resolvePeriod(
				notice(null, "2026-06-22", "6. 29.(월) 18시까지"), body, LocalDate.of(2026, 6, 20));

		assertThat(period).isEmpty();
	}

	@Test
	@DisplayName("연도 없는 표기여도 본문에 그 날짜가 없으면 버린다 — 완화가 구멍이 되지 않도록")
	void stillRejectsFabricatedEvidenceWithoutYear() {
		String body = "2026학년도 2학기 장학생을 모집합니다. 자세한 내용은 첨부파일을 참고하세요.";

		assertThat(parser.resolvePeriod(
				notice(null, "2026-08-13", "(~8/13)"), body, LocalDate.of(2026, 8, 5))).isEmpty();
	}

	@Test
	@DisplayName("근거 문장이 본문에 없으면 기간을 버린다 — 지어낸 값 차단")
	void rejectsPeriodWithFabricatedEvidence() {
		String body = "2026학년도 2학기 장학생을 모집합니다. 자세한 내용은 첨부파일을 참고하세요.";

		// LLM 이 그럴싸한 기간과 근거를 만들어낸 상황
		var period = parser.resolvePeriod(
				notice("2026-08-01", "2026-08-14", "신청기간 : 2026. 8. 1. ~ 8. 14."), body);

		assertThat(period).isEmpty();
	}

	@Test
	@DisplayName("근거 문장이 비어 있으면 기간을 버린다")
	void rejectsPeriodWithoutEvidence() {
		String body = "신청기간 : 2026. 8. 1. ~ 8. 14. 인 장학금입니다.";

		assertThat(parser.resolvePeriod(notice("2026-08-01", "2026-08-14", null), body)).isEmpty();
		assertThat(parser.resolvePeriod(notice("2026-08-01", "2026-08-14", "  "), body)).isEmpty();
	}

	@Test
	@DisplayName("공백·구두점 차이는 허용한다 — 정상 추출이 버려지지 않도록")
	void toleratesWhitespaceAndPunctuationDifference() {
		String body = "신청기간 : 2026. 8. 1. ~ 8. 14.";

		// LLM 이 공백을 정규화해 인용한 경우
		var period = parser.resolvePeriod(
				notice("2026-08-01", "2026-08-14", "신청기간: 2026.8.1.~8.14."), body);

		assertThat(period).isPresent();
	}

	@Test
	@DisplayName("너무 짧은 인용은 근거로 인정하지 않는다 — 우연한 일치 방지")
	void rejectsTooShortEvidence() {
		assertThat(UnivNoticeLlmParser.isEvidenceGrounded("8.1", "신청기간 8.1 ~ 8.14")).isFalse();
	}

	// --- 근무기간 오인 방어 ---

	@Test
	@DisplayName("근무기간 라벨이 붙은 인용은 버린다 — 300일 가드로는 못 잡는 오류")
	void rejectsWorkPeriodByLabel() {
		// 실제 오탐 사례(경희대). 근거 인용도 통과하고(본문에 실제로 있음)
		// 일수도 180일이라 300일 상한도 통과한다. 라벨을 봐야만 걸러진다.
		String body = "국가근로장학금 개요. 근무기간 : 2026. 9. 1. ~ 2027. 2. 28. 시급 10,320원.";

		var period = parser.resolvePeriod(
				notice("2026-09-01", "2027-02-28", "근무기간 : 2026. 9. 1. ~ 2027. 2. 28."), body);

		assertThat(period).isEmpty();
	}

	@Test
	@DisplayName("신청기간이 아닌 라벨들을 모두 걸러낸다")
	void rejectsAllNonApplicationLabels() {
		for (String label : new String[]{"근무기간", "근로기간", "지급기간", "게시기간",
				"심사기간", "발표기간", "수혜기간", "유효기간", "활동기간"}) {
			assertThat(UnivNoticeLlmParser.isNonApplicationPeriod(label + " : 2026. 9. 1. ~ 2026. 10. 1."))
					.as(label)
					.isTrue();
		}
	}

	@Test
	@DisplayName("신청 라벨이 함께 있으면 신청기간으로 인정한다")
	void trustsApplicationLabelWhenBothPresent() {
		assertThat(UnivNoticeLlmParser.isNonApplicationPeriod(
				"신청기간 2026. 8. 1. ~ 8. 14. (근무기간은 9월부터)")).isFalse();
	}

	@Test
	@DisplayName("라벨이 아무것도 없으면 막지 않는다 — 과잉 차단 방지")
	void doesNotBlockWhenNoLabel() {
		assertThat(UnivNoticeLlmParser.isNonApplicationPeriod("2026. 8. 1. ~ 8. 14.")).isFalse();
	}

	@Test
	@DisplayName("300일을 넘는 기간은 버린다 — 표가 뭉개져 무관한 날짜가 짝지어진 경우")
	void rejectsImplausiblyLongPeriod() {
		String body = "접수기간 매주 월요일 2026. 7. 13. 1명 ~ 2027. 6. 30. 매주 화요일";

		var period = parser.resolvePeriod(
				notice("2026-07-13", "2027-06-30", "접수기간 매주 월요일 2026. 7. 13. 1명 ~ 2027. 6. 30."), body);

		assertThat(period).isEmpty();
	}

	@Test
	@DisplayName("종료일이 시작일보다 앞서면 버린다")
	void rejectsInvertedPeriod() {
		String body = "신청기간 2026. 9. 1. ~ 2026. 8. 1.";

		assertThat(parser.resolvePeriod(
				notice("2026-09-01", "2026-08-01", "신청기간 2026. 9. 1. ~ 2026. 8. 1."), body)).isEmpty();
	}

	@Test
	@DisplayName("마감일만 있어도 인정한다 (시작일 null 허용)")
	void acceptsDeadlineOnly() {
		String body = "장학금 신청은 2026. 8. 21. 까지 받습니다.";

		var period = parser.resolvePeriod(
				notice(null, "2026-08-21", "2026. 8. 21. 까지"), body);

		assertThat(period).isPresent();
		assertThat(period.get().start()).isNull();
		assertThat(period.get().end().toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 21));
	}

	@Test
	@DisplayName("시작일만 있으면 신청기간으로 쓰지 않는다")
	void rejectsStartOnly() {
		String body = "2026. 8. 1. 부터 신청을 받습니다.";

		assertThat(parser.resolvePeriod(notice("2026-08-01", null, "2026. 8. 1. 부터"), body)).isEmpty();
	}

	@Test
	@DisplayName("날짜 형식이 깨지면 기간 없이 둔다")
	void rejectsMalformedDate() {
		String body = "신청기간 2026년 13월 45일";

		assertThat(parser.resolvePeriod(notice("2026-13-45", "2026-13-99", "신청기간 2026년 13월 45일"), body))
				.isEmpty();
	}

	// --- 금액·인원·유형 검증 ---

	@Test
	@DisplayName("금액은 1원 이상 1억 이하만 인정한다 — 예산 총액 오추출 방어")
	void validatesAmountRange() {
		assertThat(parser.resolveAmount(1_000_000L)).isEqualTo(1_000_000L);
		assertThat(parser.resolveAmount(0L)).isNull();
		assertThat(parser.resolveAmount(-1L)).isNull();
		assertThat(parser.resolveAmount(160_000_000L)).isNull();   // 사업 예산 총액
		assertThat(parser.resolveAmount(null)).isNull();
	}

	@Test
	@DisplayName("선발 인원은 1명 이상 1만명 이하만 인정한다")
	void validatesSelectionCountRange() {
		assertThat(parser.resolveSelectionCount(40)).isEqualTo(40);
		assertThat(parser.resolveSelectionCount(0)).isNull();
		assertThat(parser.resolveSelectionCount(99_999)).isNull();
		assertThat(parser.resolveSelectionCount(null)).isNull();
	}

	@Test
	@DisplayName("장학 유형은 enum 으로 변환하고, 알 수 없으면 INTERNAL 로 둔다")
	void resolvesType() {
		assertThat(parser.resolveType("WORK_STUDY")).isEqualTo(ScholarshipType.WORK_STUDY);
		assertThat(parser.resolveType("external")).isEqualTo(ScholarshipType.EXTERNAL);
		assertThat(parser.resolveType("교외")).isEqualTo(ScholarshipType.INTERNAL);
		assertThat(parser.resolveType(null)).isEqualTo(ScholarshipType.INTERNAL);
	}

	// --- 자격조건 검증 ---

	private static final String CONDITION_BODY = """
			2026학년도 2학기 성적우수 장학생을 모집합니다. 지원자격은 직전학기 평점평균 3.5 이상인 \
			2학년 이상 재학생이며, 가계 곤란으로 학업 유지가 어려운 자를 우선 선발합니다. \
			타 장학금과의 중복수혜는 불가합니다. 제출서류는 성적증명서 1부입니다.
			""";

	@Test
	@DisplayName("본문에 근거가 있는 조건만 유형별로 남긴다")
	void resolvesGroundedConditions() {
		var notice = noticeWithConditions(
				ParsedNotice.Condition.of("ACADEMIC_CRITERIA", "직전학기 평점평균 3.5 이상인"),
				ParsedNotice.Condition.of("GRADE_LEVEL", "2학년 이상 재학생이며"),
				ParsedNotice.Condition.of("RESTRICTION", "타 장학금과의 중복수혜는 불가합니다"));

		var resolved = parser.resolveConditions(notice, CONDITION_BODY);

		assertThat(resolved).extracting(UnivNoticeLlmParser.ResolvedCondition::type)
				.containsExactly(ConditionType.ACADEMIC_CRITERIA, ConditionType.GRADE_LEVEL,
						ConditionType.RESTRICTION);
		assertThat(resolved.get(0).snippet()).isEqualTo("직전학기 평점평균 3.5 이상인");
	}

	/*
	정규식 추출기가 놓치던 유형. 패턴에 숫자·키워드가 없어 잡히지 않았는데,
	이게 대학 공지에서 흔한 서술형 자격 요건이다. LLM 으로 바꾼 주된 이유.
	 */
	@Test
	@DisplayName("숫자·키워드 없는 서술형 자격 요건도 조건으로 잡는다")
	void resolvesNarrativeCondition() {
		var notice = noticeWithConditions(
				ParsedNotice.Condition.of("SPECIFIC_QUALIFICATION", "가계 곤란으로 학업 유지가 어려운 자"));

		var resolved = parser.resolveConditions(notice, CONDITION_BODY);

		assertThat(resolved).singleElement()
				.satisfies(condition -> assertThat(condition.type())
						.isEqualTo(ConditionType.SPECIFIC_QUALIFICATION));
	}

	/*
	없는 조건을 만들어내면 자격 있는 학생이 추천에서 조용히 탈락한다.
	기간과 같은 이유로, 본문에 없는 인용은 통째로 버린다.
	 */
	@Test
	@DisplayName("본문에 없는 문장을 인용한 조건은 버린다 — 환각 방어")
	void dropsHallucinatedCondition() {
		var notice = noticeWithConditions(
				ParsedNotice.Condition.of("INCOME_CRITERIA", "소득 3분위 이하인 학생만 지원 가능합니다"),
				ParsedNotice.Condition.of("GRADE_LEVEL", "2학년 이상 재학생이며"));

		var resolved = parser.resolveConditions(notice, CONDITION_BODY);

		assertThat(resolved).extracting(UnivNoticeLlmParser.ResolvedCondition::type)
				.containsExactly(ConditionType.GRADE_LEVEL);
	}

	@Test
	@DisplayName("알 수 없는 조건 유형은 버린다")
	void dropsUnknownType() {
		var notice = noticeWithConditions(
				ParsedNotice.Condition.of("성적", "직전학기 평점평균 3.5 이상인"),
				ParsedNotice.Condition.of(null, "2학년 이상 재학생이며"));

		assertThat(parser.resolveConditions(notice, CONDITION_BODY)).isEmpty();
	}

	@Test
	@DisplayName("유형·문장이 같은 조건은 한 번만 남긴다")
	void deduplicates() {
		var notice = noticeWithConditions(
				ParsedNotice.Condition.of("GRADE_LEVEL", "2학년 이상 재학생이며"),
				ParsedNotice.Condition.of("GRADE_LEVEL", "2학년 이상 재학생이며"));

		assertThat(parser.resolveConditions(notice, CONDITION_BODY)).hasSize(1);
	}

	@Test
	@DisplayName("조건이 12개를 넘으면 앞에서부터 12개만 저장한다")
	void capsConditionCount() {
		// 근거 검증을 통과해야 하므로, 인용할 문장 20개를 실제로 담은 본문을 만든다
		var body = new StringBuilder();
		var conditions = new ParsedNotice.Condition[20];
		for (int i = 0; i < conditions.length; i++) {
			String sentence = "지원자격 세부요건 " + i + " 번 항목을 충족해야 합니다.";
			body.append(sentence).append(' ');
			conditions[i] = ParsedNotice.Condition.of("SPECIFIC_QUALIFICATION", sentence);
		}

		assertThat(parser.resolveConditions(noticeWithConditions(conditions), body.toString()))
				.hasSize(12);
	}

	@Test
	@DisplayName("conditions 가 없으면 빈 목록을 돌려준다")
	void handlesMissingConditions() {
		assertThat(parser.resolveConditions(notice(null, null, null), CONDITION_BODY)).isEmpty();
	}

	@Test
	@DisplayName("열거 필드에 union 타입을 함께 선언하지 않는다 — API 가 400 으로 거부한다")
	void nullableEnumFieldsDeclareNoType() {
		// 실제로 겪은 실패:
		//   Enum value 'INTERNAL' does not match declared type '['string', 'null']'
		// 요청이 통째로 반려돼 파서가 한 건도 처리하지 못했다. 스키마는 눈으로 검토해서는
		// 안 걸리고 실제 호출을 해봐야 드러나므로 여기서 막는다.
		// (운영 키로 확인함: type 이 단일 문자열이면 enum 과 같이 써도 통과한다)
		Map<String, Object> schema = parser.buildRequest(null, "본문".repeat(50)).outputSchema();
		assertNoUnionTypeWithEnum(schema, "$");
	}

	@SuppressWarnings("unchecked")
	private void assertNoUnionTypeWithEnum(Object node, String path) {
		if (node instanceof Map<?, ?> map) {
			// {"type":"string","enum":[...]} 는 정상이다. 거부당하는 건 타입이 목록인 경우뿐이다.
			if (map.containsKey("enum") && map.get("type") instanceof List<?>) {
				throw new AssertionError(
						"enum 에 union 타입을 같이 선언했다(API 가 거부한다): " + path + " -> " + map);
			}
			map.forEach((key, value) -> assertNoUnionTypeWithEnum(value, path + "." + key));
		} else if (node instanceof List<?> list) {
			list.forEach(item -> assertNoUnionTypeWithEnum(item, path + "[]"));
		}
	}

	@Test
	@DisplayName("제목을 함께 보낸다 — 본문 영역 밖에 제목이 있는 게시판이 있다")
	void sendsTitleAlongsideBody() {
		// 건국대는 제목이 본문 영역 밖에 있어, 본문만 보내면 LLM 이 제목을 볼 수가 없다.
		// 게다가 제목에 기간이 든 공고가 많다 — "…모집(6. 22. ~ 7. 24.)".
		var request = parser.buildRequest("[교외] 2026년 종근당고촌재단 무상기숙사 장학생 모집(6. 22. ~ 7. 24.)",
				"1. 선발대상: 붙임 참조 2. 접수기간: 2026. 6. 22.(월) ~ 7. 24.(금)");

		String sent = request.messages().get(0).content();
		assertThat(sent).contains("[제목]").contains("종근당고촌재단");
		assertThat(sent).contains("[본문]").contains("접수기간");
	}

	@Test
	@DisplayName("제목이 없으면 본문만 보낸다 — 빈 제목 표시를 붙이지 않는다")
	void sendsBodyOnlyWhenTitleMissing() {
		String sent = parser.buildRequest(null, "본문만 있는 공지입니다.").messages().get(0).content();

		assertThat(sent).doesNotContain("[제목]");
		assertThat(sent).contains("[본문]");
	}

	/*
	자소서·면접은 사용자가 지원할지 말지를 정하는 정보다. boolean 으로 두면 "명시적으로 없음" 과
	"언급 없음" 이 똑같이 false 가 되는데, 그러면 면접이 있는 장학금을 없다고 표시하게 된다.
	 */
	@Test
	@DisplayName("근거 문장이 본문에 있어야 전형 판단을 받아들인다")
	void acceptsRequirementOnlyWithGroundedEvidence() {
		String body = "서류 합격자에 한해 면접전형을 진행합니다. 자기소개서는 전원 제출입니다.";

		var interview = parser.resolveRequirement("CONDITIONAL", "서류 합격자에 한해 면접전형을 진행합니다", body, null);
		assertThat(interview.level()).isEqualTo(RequirementLevel.CONDITIONAL);
		assertThat(interview.evidence()).contains("서류 합격자에 한해");
	}

	@Test
	@DisplayName("본문에 없는 문장을 근거로 대면 판단을 버린다 — 지어낸 값이 더 위험하다")
	void dropsRequirementWhenEvidenceIsInvented() {
		String body = "2026학년도 2학기 장학생을 모집합니다.";

		var made = parser.resolveRequirement("REQUIRED", "면접전형을 실시합니다", body, null);

		assertThat(made.level()).isNull();
		assertThat(made.evidence()).isNull();
	}

	@Test
	@DisplayName("근거 없이 값만 오면 버리고, 값이 없으면 모름으로 둔다")
	void requiresEvidenceAndKeepsUnknown() {
		String body = "서류 합격자에 한해 면접전형을 진행합니다.";

		assertThat(parser.resolveRequirement("REQUIRED", null, body, null).level()).isNull();
		assertThat(parser.resolveRequirement("REQUIRED", "  ", body, null).level()).isNull();
		// 공고에 언급이 없으면 null — "없다" 로 단정하지 않는다.
		assertThat(parser.resolveRequirement(null, null, body, null).level()).isNull();
		// 이상한 값이 와도 터지지 않는다.
		assertThat(parser.resolveRequirement("MAYBE", "서류 합격자에 한해", body, null).level()).isNull();
	}

	@Test
	@DisplayName("제목에만 적힌 근거도 인정한다")
	void acceptsEvidenceFromTitle() {
		var result = parser.resolveRequirement("REQUIRED", "면접전형 진행",
				"본문에는 일정만 있습니다.", "2026 장학생 모집 (면접전형 진행)");

		assertThat(result.level()).isEqualTo(RequirementLevel.REQUIRED);
	}

	/*
	오프라인 제출 공고가 마감일 손실의 원인이었다. 온라인은 "신청기간 : A ~ B" 로 깔끔한데
	우편·방문은 "도착분에 한함", "우편 소인" 처럼 제각각이라, LLM 이 인용을 다듬다가 대조에 걸렸다.
	 */
	@Test
	@DisplayName("인용 끝이 달라도 날짜가 본문에 있으면 기간을 살린다")
	void keepsPeriodWhenQuotedDateExistsInBody() {
		String body = "8. 제출방법 : 네이버폼 9. 접수마감 : ~2026.07.30(목) 오전 10:00 도착분에 한함";
		// LLM 이 "도착분에 한함" 을 "까지" 로 바꿔 썼다. 뜻은 같지만 원문은 아니다.
		var parsed = parser.resolvePeriod(
				notice(null, "2026-07-30", "~2026.07.30(목) 오전 10:00까지"), body);

		assertThat(parsed).isPresent();
		assertThat(parsed.get().end().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 7, 30));
	}

	@Test
	@DisplayName("인용에 있는 날짜가 본문에 없으면 지어낸 것으로 보고 버린다")
	void dropsPeriodWhenQuotedDateIsAbsent() {
		String body = "접수마감 : ~2026.07.30(목) 오전 10:00 도착분에 한함";

		assertThat(parser.resolvePeriod(
				notice(null, "2026-08-15", "~2026.08.15(금)까지"), body)).isEmpty();
	}

	@Test
	@DisplayName("공지 종류·제출 경로는 이상한 값이 와도 null 로 떨어진다")
	void resolvesEnumsSafely() {
		assertThat(parser.resolveNoticeKind("RECRUITMENT")).isEqualTo(NoticeKind.RECRUITMENT);
		assertThat(parser.resolveNoticeKind("recruitment")).isEqualTo(NoticeKind.RECRUITMENT);
		assertThat(parser.resolveNoticeKind("???")).isNull();
		assertThat(parser.resolveNoticeKind(null)).isNull();

		assertThat(parser.resolveSubmissionChannel("MIXED")).isEqualTo(SubmissionChannel.MIXED);
		assertThat(parser.resolveSubmissionChannel("카카오톡")).isNull();
	}

	@Test
	@DisplayName("근거에 날짜가 하나뿐이면 시작일을 버린다 — 하루만 접수하는 것처럼 보인다")
	void dropsStartWhenEvidenceHasSingleDate() {
		String body = "□ 지원방법 ㅇ 서류제출기간 : 2026. 8. 2.(일) 까지 ㅇ 제출방법 : 이메일 제출";
		// LLM 이 시작일에도 같은 날짜를 넣었다. 화면에는 "8/2 ~ 8/2" 로 나온다.
		var parsed = parser.resolvePeriod(
				notice("2026-08-02", "2026-08-02", "서류제출기간 : 2026. 8. 2.(일) 까지"), body);

		assertThat(parsed).isPresent();
		assertThat(parsed.get().start()).isNull();
		assertThat(parsed.get().end().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 2));
	}

	@Test
	@DisplayName("근거에 날짜가 둘이면 시작일을 그대로 둔다")
	void keepsStartWhenEvidenceHasTwoDates() {
		String body = "2. 신청기간 : 2026. 07. 21.(화) ~ 2026. 08. 06.(목) 자정까지";
		var parsed = parser.resolvePeriod(
				notice("2026-07-21", "2026-08-06", "신청기간 : 2026. 07. 21.(화) ~ 2026. 08. 06.(목)"), body);

		assertThat(parsed).isPresent();
		assertThat(parsed.get().start().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 7, 21));
	}
}
