/*
 * 관리자 콘솔 "작업 기록"(감사 로그) 표시. admin-core.js(WC) 다음, admin-console.js 앞에 읽는다.
 *
 * 감사 로그의 전후 스냅샷(JSON)을 사람이 읽는 변경 목록으로 바꾼다.
 * - 의미 있는 변경과 자동으로 바뀌는 시스템 필드(수정 시각·검수 완료 등)를 나눈다
 * - 내부 경로(scholarship.applicationEndAt) 대신 한글 라벨, 값은 날짜·금액·예/아니오·한글 enum 으로
 * - 긴 글은 단어 단위 차이, 목록(조건·서류·선발 일정)은 행 ID 가 아니라 내용으로 같은 행을 찾는다
 *
 * 아래 "순수 함수"는 DOM 을 쓰지 않아 node 로 테스트한다(src/test/resources/admin-js/audit.test.mjs).
 */
(root => {
	'use strict';

	const WC = root.WC;
	const {esc, fmt, label, L} = WC;
	const EMPTY = '(비어 있음)';

	/* ================================================================== 필드 분류 */

	/** 저장할 때마다 시스템이 바꾸는 필드. 기본 화면에서는 "자동 변경 항목" 접기 안에 둔다. */
	const SYSTEM_FIELDS = ['id', 'createdAt', 'updatedAt', 'verified', 'active', 'primarySource', 'timelineLocked'];
	/** 한쪽 스냅샷에만 있을 수 있는 필드(2026-10 이후 추가). 양쪽에 다 있을 때만 비교한다. 없음 ≠ 빈 값. */
	const LATER_FIELDS = ['timeline', 'periodLocked', 'timelineLocked'];
	const LIST_FIELDS = ['conditions', 'documents', 'timeline', 'images'];
	const BIZ_DATES = ['applicationStartAt', 'applicationEndAt'];
	const TIMESTAMPS = ['createdAt', 'updatedAt', 'deletedAt'];
	const BOOLS = ['combined', 'periodLocked', 'timelineLocked', 'verified', 'active'];
	const ENUMS = {scholarshipType: 'scholarshipType', recruitmentStatus: 'recruitment', noticeKind: 'noticeKind',
		submissionChannel: 'channel', essayRequirement: 'requirement', interviewRequirement: 'requirement'};
	const LONG_TEXT = ['summary', 'description', 'submissionEvidence', 'essayEvidence', 'interviewEvidence'];

	const fieldLabel = key => (L.field || {})[key] || key;
	const isEmpty = value => value == null || value === '' || (Array.isArray(value) && !value.length);

	/** 스냅샷 모양을 하나로: {fields: 장학금 필드, lists: 목록 필드}. 통합 수정은 scholarship 객체로 묶여 있다. */
	function normalizeSnapshot(snapshot) {
		if (!snapshot || typeof snapshot !== 'object' || Array.isArray(snapshot)) return null;
		const nested = snapshot.scholarship && typeof snapshot.scholarship === 'object';
		const fields = Object.assign({}, nested ? snapshot.scholarship : snapshot);
		const lists = {};
		LIST_FIELDS.forEach(key => {
			const source = nested ? snapshot : fields;
			if (key in source) lists[key] = Array.isArray(source[key]) ? source[key] : [];
			delete fields[key];
		});
		return {fields, lists, kind: nested ? 'AGGREGATE' : 'FLAT'};
	}

	/* ================================================================== 값 형식 */

	const minuteOf = value => value == null ? null : String(value).replace(' ', 'T').slice(0, 16);
	const secondOf = value => value == null ? null : String(value).replace(' ', 'T').slice(0, 19);

	/** 비교용 값. 날짜는 초까지(마이크로초 차이는 같은 값), 빈 문자열은 null. */
	function comparable(key, value) {
		if (value === '' || value === undefined) return null;
		if ((BIZ_DATES.includes(key) || TIMESTAMPS.includes(key)) && value != null) return secondOf(value);
		return value;
	}
	const sameValue = (key, a, b) => JSON.stringify(comparable(key, a)) === JSON.stringify(comparable(key, b));

	/** 두 날짜가 분까지 같고 초만 다른가. 그럴 때만 초를 보여 준다. */
	const onlySecondsDiffer = (a, b) => a != null && b != null && minuteOf(a) === minuteOf(b) && secondOf(a) !== secondOf(b);

	/**
	 * 값 하나를 화면 문장으로. other 는 짝이 되는 값(날짜의 초 표시 여부 판단용).
	 * 날짜 "2026.11.11 (수) 23:59", 금액 "1,000,000원", 참/거짓 "예/아니오", enum 은 한글, 빈 값 "(비어 있음)".
	 */
	function formatValue(key, value, other) {
		if (isEmpty(value)) return EMPTY;
		const seconds = onlySecondsDiffer(value, other);
		if (BIZ_DATES.includes(key)) return fmt.bizDay(value, seconds);
		if (TIMESTAMPS.includes(key)) return fmt.tsDay(value, seconds);
		if (BOOLS.includes(key)) return value === true || value === 'true' ? '예' : '아니오';
		if (key === 'amount') return Number(value).toLocaleString('ko-KR') + '원';
		if (key === 'selectionCount') return Number(value).toLocaleString('ko-KR') + '명';
		if (ENUMS[key]) return label(ENUMS[key], value);
		if (typeof value === 'object') return JSON.stringify(value);
		return String(value);
	}

	/* ================================================================== 목록 행 */

	const rangeText = (min, max) => min == null && max == null ? '' : min != null && max != null && min !== max ? min + '~' + max : String(min != null ? min : max);

	/** 조건 한 줄. 예: "성적 · 필수 자격 · 이상 · 직전 학기 평균 3.5 이상 (3.5)" */
	function conditionSentence(c) {
		const range = rangeText(c.valueInt, c.valueIntMax);
		return [label('conditionType', c.conditionType, '유형 없음'), label('necessity', c.necessity, '필수 여부 없음'),
			label('operator', c.operator, '비교 방식 없음'), (c.valueString || '(조건 문장 없음)') + (range ? ' (' + range + ')' : '')].join(' · ');
	}
	const documentSentence = d => (d.name || '(서류명 없음)') + (d.essay ? ' · 자기소개서' : '') + (d.downloadUrl ? ' · 양식 링크 있음' : '');
	function timelineDate(t) {
		if (t.dateType === 'TBD') return t.dateText || '미정';
		if (t.dateType === 'RANGE') return fmt.bizDay(t.startDate) + ' ~ ' + fmt.bizDay(t.endDate);
		return t.startDate ? fmt.bizDay(t.startDate) : '날짜 없음';
	}
	const timelineSentence = t => [(t.title && String(t.title).trim()) || label('stage', t.stageCode, '단계 없음'), timelineDate(t),
		t.note ? String(t.note) : null].filter(Boolean).join(' · ');
	const imageSentence = i => (i.originalName || i.imageType || '이미지') + (i.sourceUrl ? ' · 원본 ' + i.sourceUrl : '');

	const sortedRefs = refs => (refs || []).map(r => r.refId != null ? 'id:' + r.refId : 'code:' + r.refCode).sort();

	/**
	 * 목록 종류별 규칙.
	 * key   : 같은 행인지 판단하는 내용(행 ID·순서는 뺀다 — 통합 수정은 저장할 때마다 행을 새로 만들어 ID 가 바뀐다)
	 * pair  : 내용이 달라진 행끼리 "변경"으로 짝지을 기준(조건 유형·서류명·일정 단계)
	 */
	const LIST_RULES = {
		conditions: {
			key: c => JSON.stringify([c.conditionType, c.necessity, c.operator, c.valueInt ?? null, c.valueIntMax ?? null,
				c.valueString || null, Boolean(c.autoExtracted), sortedRefs(c.refs)]),
			pair: c => c.conditionType, sentence: conditionSentence
		},
		documents: {
			key: d => JSON.stringify([d.name || null, Boolean(d.essay), d.downloadUrl || null]),
			pair: d => d.name, sentence: documentSentence
		},
		timeline: {
			key: t => JSON.stringify([t.stageCode, (t.title && String(t.title).trim()) || null, t.dateType || 'SINGLE',
				t.dateType === 'TBD' ? null : t.startDate || null, t.dateType === 'TBD' ? null : t.endDate || null,
				t.dateType === 'TBD' ? t.dateText || null : null, t.note || null, t.evidence || null]),
			pair: t => t.stageCode, sentence: timelineSentence
		},
		images: {key: i => JSON.stringify([i.sourceUrl || null, i.originalName || null]), pair: i => i.imageType, sentence: imageSentence}
	};

	/**
	 * 행 단위 비교. 내용이 같은 행은 "그대로", 남은 행 중 pair 가 같은 것끼리 "변경", 나머지는 추가·삭제.
	 * @return {same, added, removed, changed: [{before, after}], orderOnly, total}
	 */
	function listDiff(kind, oldRows, newRows) {
		const rule = LIST_RULES[kind] || LIST_RULES.documents;
		const removed = (oldRows || []).slice(), added = (newRows || []).slice(), same = [];
		for (let i = removed.length - 1; i >= 0; i--) {
			const match = added.findIndex(row => rule.key(row) === rule.key(removed[i]));
			if (match >= 0) { same.unshift(added[match]); added.splice(match, 1); removed.splice(i, 1); }
		}
		const changed = [];
		for (let i = 0; i < removed.length; i++) {
			const pair = rule.pair(removed[i]);
			const match = pair == null ? -1 : added.findIndex(row => rule.pair(row) === pair);
			if (match < 0) continue;
			changed.push({before: removed[i], after: added[match]});
			added.splice(match, 1); removed.splice(i, 1); i--;
		}
		const oldKeys = (oldRows || []).map(rule.key), newKeys = (newRows || []).map(rule.key);
		const orderOnly = !added.length && !removed.length && !changed.length && JSON.stringify(oldKeys) !== JSON.stringify(newKeys);
		return {same, added, removed, changed, orderOnly, total: added.length + removed.length + changed.length};
	}

	/* ================================================================== 긴 글: 단어 단위 차이 */

	const tokenize = text => String(text || '').split(/(\s+)/).filter(token => token !== '');

	/** LCS 로 단어 단위 차이. 결과는 [{op: '=' | '+' | '-', text}] (이웃한 같은 op 는 합친다). 너무 길면 통째 교체. */
	function wordDiff(before, after) {
		const a = tokenize(before), b = tokenize(after);
		if (a.length * b.length > 400000) return [{op: '-', text: a.join('')}, {op: '+', text: b.join('')}].filter(part => part.text);
		const dp = Array.from({length: a.length + 1}, () => new Uint32Array(b.length + 1));
		for (let i = a.length - 1; i >= 0; i--) {
			for (let j = b.length - 1; j >= 0; j--) dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
		}
		const parts = [];
		const push = (op, text) => { const last = parts[parts.length - 1]; if (last && last.op === op) last.text += text; else parts.push({op, text}); };
		let i = 0, j = 0;
		while (i < a.length && j < b.length) {
			if (a[i] === b[j]) { push('=', a[i]); i++; j++; } else if (dp[i + 1][j] >= dp[i][j + 1]) { push('-', a[i]); i++; } else { push('+', b[j]); j++; }
		}
		while (i < a.length) push('-', a[i++]);
		while (j < b.length) push('+', b[j++]);
		return parts;
	}

	/* ================================================================== 스냅샷 비교 */

	/**
	 * 전후 스냅샷을 변경 항목으로. 둘 다 객체가 아니면 null(화면은 원본 그대로 보여 준다).
	 * @return {changes: 의미 있는 변경[], system: 자동 변경[]}. 항목 = {key, label, type: value|text|list, ...}
	 */
	function diffSnapshots(beforeSnapshot, afterSnapshot) {
		const before = normalizeSnapshot(beforeSnapshot), after = normalizeSnapshot(afterSnapshot);
		if (!before || !after) return null;
		const order = Object.keys(L.field || {});
		const keys = [...new Set([...Object.keys(before.fields), ...Object.keys(after.fields)])]
			.sort((x, y) => (order.indexOf(x) < 0 ? 999 : order.indexOf(x)) - (order.indexOf(y) < 0 ? 999 : order.indexOf(y)));
		const changes = [], system = [];
		keys.forEach(key => {
			if (LATER_FIELDS.includes(key) && !(key in before.fields && key in after.fields)) return;
			const from = before.fields[key], to = after.fields[key];
			if (sameValue(key, from, to)) return;
			const item = LONG_TEXT.includes(key) && !isEmpty(from) && !isEmpty(to)
				? {key, label: fieldLabel(key), type: 'text', parts: wordDiff(from, to)}
				: {key, label: fieldLabel(key), type: 'value', before: formatValue(key, from, to), after: formatValue(key, to, from)};
			(SYSTEM_FIELDS.includes(key) ? system : changes).push(item);
		});
		LIST_FIELDS.forEach(key => {
			if (!(key in before.lists) || !(key in after.lists)) return;
			const diff = listDiff(key, before.lists[key], after.lists[key]);
			if (!diff.total && !diff.orderOnly) return;
			changes.push({key, label: fieldLabel(key), type: 'list', diff, beforeCount: before.lists[key].length, afterCount: after.lists[key].length});
		});
		return {changes, system};
	}

	/* ================================================================== 행위·병합 요약 */

	/** 행위 코드 → {known, label, code}. 모르는 코드(새로 생긴 작업)는 코드를 그대로 이름으로 쓴다. */
	function actionView(code) {
		const name = (L.action || {})[code];
		return {known: Boolean(name), label: name || String(code || '-'), code: String(code || '')};
	}

	const MOVED_LABELS = {
		'scrap.deletedDuplicate': '양쪽에 겹친 스크랩 정리', 'scrap.moved': '스크랩', 'essay.moved': '자기소개서', 'report.moved': '신고',
		'dispatchLog.moved': '알림 발송 이력', 'recommendation.moved': '추천 기록', 'event.moved': '노출·클릭 기록', 'rawScholarship.moved': '수집 원문',
		'image.moved': '포스터 이미지', 'image.keptOnDuplicate': '내린 쪽에 남긴 이미지', 'interviewPrep.moved': '면접 예상 질문',
		'interviewPrep.keptOnDuplicate': '내린 쪽에 남긴 면접 질문', 'timeline.moved': '선발 일정', 'timeline.deletedOnDuplicate': '내린 쪽 선발 일정 삭제',
		'conditionRef.deleted': '내린 쪽 조건 참조 삭제', 'condition.deleted': '내린 쪽 조건 삭제', 'document.deleted': '내린 쪽 서류 삭제'
	};

	/**
	 * 병합·중복 아님 기록의 요약 문구를 읽는다. 서버 MergeAuditDetail 형식:
	 *   "[일괄 병합 12건 중 3번째 · ]중복 307 를 683 로 병합. {scrap.moved=2, ...}"
	 *   "[일괄 중복 아님 2건 중 1번째 · ]중복 후보 12 반려 (683 vs 307). 사유"
	 * 요약은 1000자에서 잘릴 수 있어 옮긴 건수는 읽힌 만큼만 준다.
	 */
	function parseMergeDetail(action, detail) {
		const text = String(detail || '');
		const bulk = /^일괄 [^·]+ (\d+)건 중 (\d+)번째 · /.exec(text);
		const result = {bulk: bulk ? {total: Number(bulk[1]), position: Number(bulk[2])} : null};
		if (action === 'SCHOLARSHIP_MERGE') {
			const m = /중복 (\d+) 를 (\d+) 로 병합/.exec(text);
			if (!m) return null;
			Object.assign(result, {dropId: Number(m[1]), keepId: Number(m[2]), moved: []});
			const map = /\{([^}]*)\}?/.exec(text.slice(m.index));
			if (map) {
				map[1].split(',').map(part => part.trim().split('=')).filter(kv => kv.length === 2 && /^\d+$/.test(kv[1]))
					.forEach(([key, count]) => result.moved.push({key, label: MOVED_LABELS[key] || key, count: Number(count)}));
			}
			return result;
		}
		if (action === 'SCHOLARSHIP_MERGE_REJECT') {
			const m = /중복 후보 (\d+) 반려 \((\d+) vs (\d+)\)\.\s?(.*)$/s.exec(text);
			if (!m) return null;
			return Object.assign(result, {candidateId: Number(m[1]), keepId: Number(m[2]), dropId: Number(m[3]), note: m[4].trim() || null});
		}
		return null;
	}

	/* ================================================================== 등록·내리기 내용 */

	/** 등록 내용 화면의 구성(오른쪽 상세 패널과 같은 묶음). */
	const SECTIONS = [
		['기본 정보', ['title', 'provider', 'scholarshipType', 'recruitmentStatus', 'noticeKind', 'combined', 'contact', 'homepageUrl', 'detailUrl']],
		['모집기간', ['applicationStartAt', 'applicationEndAt', 'periodLocked']],
		['금액·인원', ['amount', 'selectionCount']],
		['요약·설명', ['summary', 'description']],
		['제출·자기소개서·면접', ['submissionChannel', 'submissionMethod', 'submissionEvidence', 'essayRequirement', 'essayEvidence',
			'interviewRequirement', 'interviewEvidence']]
	];
	/** 비어 있으면 "입력하지 않은 항목"으로 알릴 필드(선택 입력이라도 사용자 화면에 빈 칸으로 보이는 것). */
	const EXPECTED = ['provider', 'applicationStartAt', 'applicationEndAt', 'amount', 'selectionCount', 'summary', 'description',
		'homepageUrl', 'detailUrl', 'contact', 'submissionChannel', 'submissionMethod'];

	/** 스냅샷 하나를 섹션별 [라벨, 값] 목록과 빈 항목으로. 스냅샷에 없는 필드는 건너뛴다(옛 기록). */
	function snapshotSections(snapshot) {
		const snap = normalizeSnapshot(snapshot);
		if (!snap) return null;
		const sections = SECTIONS.map(([title, keys]) => ({title, rows: keys.filter(key => key in snap.fields && !isEmpty(snap.fields[key]))
			.map(key => ({key, label: fieldLabel(key), value: formatValue(key, snap.fields[key])}))})).filter(section => section.rows.length);
		const lists = LIST_FIELDS.filter(key => key in snap.lists).map(key => ({key, label: fieldLabel(key),
			rows: snap.lists[key].map(row => ({text: (LIST_RULES[key] || LIST_RULES.documents).sentence(row), autoExtracted: Boolean(row.autoExtracted)}))}));
		const missing = EXPECTED.filter(key => key in snap.fields && isEmpty(snap.fields[key])).map(fieldLabel)
			.concat(lists.filter(list => !list.rows.length && list.key !== 'images').map(list => list.label));
		return {sections, lists, missing, deletedAt: snap.fields.deletedAt || null};
	}

	/* ================================================================== HTML */

	const AUTO_TIP = 'autoExtracted=true. LLM 조건 재추출 대상에서 뺀다는 표시입니다. 수기로 저장한 조건도 true 로 저장되므로 "LLM 이 만든 조건"이라는 뜻은 아닙니다.';

	function textDiffHtml(parts) {
		const CONTEXT = 8;
		return '<div class="wdiff">' + parts.map((part, index) => {
			if (part.op === '+') return '<ins>' + esc(part.text) + '</ins>';
			if (part.op === '-') return '<del>' + esc(part.text) + '</del>';
			const words = tokenize(part.text);
			if (words.length <= CONTEXT * 2 + 4) return esc(part.text);
			const head = index === 0 ? [] : words.slice(0, CONTEXT), tail = index === parts.length - 1 ? [] : words.slice(-CONTEXT);
			const hidden = words.slice(head.length, words.length - tail.length).join('');
			return esc(head.join('')) + '<details class="wdiff-more"><summary>… 그대로인 부분 펼치기</summary>' + esc(hidden) + '</details>' + esc(tail.join(''));
		}).join('') + '</div>';
	}

	const rowLine = (sign, cls, text, extra = '') => '<li class="row-' + cls + '"><span class="row-sign">' + sign + '</span><span>' + esc(text) + extra + '</span></li>';
	const autoBadge = row => row && row.autoExtracted ? ' <span class="badge" title="' + esc(AUTO_TIP) + '">자동 추출 표시</span>' : '';

	function listDiffHtml(item) {
		const rule = LIST_RULES[item.key] || LIST_RULES.documents, diff = item.diff;
		const lines = []
			.concat(diff.added.map(row => rowLine('+', 'add', rule.sentence(row), autoBadge(row))))
			.concat(diff.removed.map(row => rowLine('−', 'del', rule.sentence(row), autoBadge(row))))
			.concat(diff.changed.map(pair => '<li class="row-chg"><span class="row-sign">~</span><span><span class="before">' + esc(rule.sentence(pair.before)) +
				autoBadge(pair.before) + '</span><span class="arrow-down" aria-hidden="true">↓</span><span class="after">' + esc(rule.sentence(pair.after)) + autoBadge(pair.after) + '</span></span></li>'));
		return '<ul class="row-diff">' + lines.join('') + (diff.orderOnly ? '<li class="row-same"><span class="row-sign">↕</span><span>내용은 같고 순서만 바뀌었습니다.</span></li>' : '') + '</ul>' +
			(diff.same.length ? '<details class="row-same-box"><summary>변경 없는 행 ' + diff.same.length + '개 보기</summary><ul class="row-diff">' +
				diff.same.map(row => rowLine('·', 'same', rule.sentence(row), autoBadge(row))).join('') + '</ul></details>' : '');
	}

	function changeHtml(item) {
		const head = '<div class="chg-label">' + esc(item.label) + (item.type === 'list' ? ' <span class="text-muted">(' + item.beforeCount + '개 → ' + item.afterCount + '개)</span>' : '') + '</div>';
		if (item.type === 'list') return '<div class="chg">' + head + listDiffHtml(item) + '</div>';
		if (item.type === 'text') return '<div class="chg">' + head + textDiffHtml(item.parts) + '</div>';
		return '<div class="chg">' + head + '<div class="chg-val"><span class="before">' + esc(item.before) + '</span><span class="arrow" aria-hidden="true">→</span>' +
			'<span class="after">' + esc(item.after) + '</span></div></div>';
	}

	/** 개발자용: 예전 방식(필드 경로·JSON 그대로) 비교표. "원본 데이터 보기" 안에 둔다. */
	function rawCompareHtml(beforeJson, afterJson) {
		const parse = text => { try { return JSON.parse(text); } catch (ignored) { return text; } };
		const flatten = value => {
			if (!value || typeof value !== 'object' || Array.isArray(value)) return value;
			const out = {};
			Object.entries(value).forEach(([key, inner]) => {
				if (inner && typeof inner === 'object' && !Array.isArray(inner)) Object.entries(inner).forEach(([k, v]) => { out[key + '.' + k] = v; });
				else out[key] = inner;
			});
			return out;
		};
		const show = value => value == null || value === '' ? EMPTY : typeof value === 'object' ? JSON.stringify(value, null, 1) : String(value);
		const before = flatten(parse(beforeJson)), after = flatten(parse(afterJson));
		if (!before || !after || typeof before !== 'object' || typeof after !== 'object') {
			return '<div class="raw">이전: ' + esc(show(before)) + '\n\n이후: ' + esc(show(after)) + '</div>';
		}
		const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])].filter(key => JSON.stringify(before[key]) !== JSON.stringify(after[key]));
		return keys.length ? '<div class="table-wrap" style="max-height:320px"><table class="diff-table"><thead><tr><th>필드</th><th>이전</th><th>이후</th></tr></thead><tbody>' +
			keys.map(key => '<tr><td class="mono">' + esc(key) + '</td><td class="val before">' + esc(show(before[key])) + '</td><td class="val after">' +
				esc(show(after[key])) + '</td></tr>').join('') + '</tbody></table></div>' : '<p class="text-muted text-sm">원본 JSON 에도 다른 필드가 없습니다.</p>';
	}

	const rawToggle = row => '<details class="raw-toggle"><summary>원본 데이터 보기(개발자용)</summary>' + rawCompareHtml(row.beforeJson, row.afterJson) + '</details>';

	/** 전후 비교 본문. */
	function diffBodyHtml(row) {
		const parse = text => { try { return JSON.parse(text); } catch (ignored) { return null; } };
		const result = diffSnapshots(parse(row.beforeJson), parse(row.afterJson));
		if (!result) return '<div class="notice neutral">이 기록은 비교할 수 있는 형식이 아닙니다. 아래 원본 데이터를 보세요.</div>' + rawToggle(row);
		const main = result.changes.length ? result.changes.map(changeHtml).join('')
			: '<div class="state compact"><b>내용이 바뀐 항목이 없습니다.</b><div>저장만 하고 값은 그대로였거나, 자동으로 바뀌는 항목만 달라졌습니다.</div></div>';
		const system = result.system.length ? '<details class="sys-box"><summary>자동 변경 항목 ' + result.system.length + '개</summary>' +
			'<p class="field-help">저장할 때 시스템이 바꾸는 값입니다(수정 시각, 검수 완료 표시, 사용자 노출 등).</p>' + result.system.map(changeHtml).join('') + '</details>' : '';
		return '<div class="chg-list">' + main + '</div>' + system + rawToggle(row);
	}

	function sectionsHtml(view) {
		return view.sections.map(section => '<div class="audit-section"><h3>' + esc(section.title) + '</h3><dl class="kv">' +
			section.rows.map(r => '<dt>' + esc(r.label) + '</dt><dd>' + esc(r.value) + '</dd>').join('') + '</dl></div>').join('') +
			view.lists.map(list => '<div class="audit-section"><h3>' + esc(list.label) + ' ' + list.rows.length + '개</h3>' + (list.rows.length
				? '<ul class="row-diff">' + list.rows.map(r => '<li class="row-same"><span class="row-sign">·</span><span>' + esc(r.text) + autoBadge(r) + '</span></li>').join('') + '</ul>'
				: '<p class="text-muted text-sm">없음</p>') + '</div>').join('');
	}

	/** 등록 기록: 등록 직후 스냅샷으로 "무엇을 어떻게 등록했는지". 옛 기록은 스냅샷이 없다. */
	function createBodyHtml(row) {
		let snapshot = null;
		try { snapshot = JSON.parse(row.afterJson); } catch (ignored) { snapshot = null; }
		const view = snapshotSections(snapshot);
		if (!view) {
			return '<div class="notice neutral"><b>등록 당시 내용이 기록되지 않았습니다.</b> 이 기능 전에 남은 기록이라 등록 요약만 있습니다.</div>' +
				(row.targetId ? '<div class="actions"><button type="button" class="btn btn-sm btn-primary" data-audit-open-current="' + row.targetId + '">현재 내용 보기</button>' +
					'<span class="text-sm text-muted">전체 장학금에서 지금 상태를 엽니다(그 뒤 수정됐을 수 있음).</span></div>' : '');
		}
		return (view.missing.length ? '<div class="notice warn"><b>입력하지 않은 항목:</b> ' + esc(view.missing.join(', ')) + '</div>' : '') +
			sectionsHtml(view) + '<p class="field-help">포스터 이미지는 등록 기록에 담기지 않습니다. 요약의 "이미지" 값과 [이미지 관리]를 보세요.</p>' + rawToggle(row);
	}

	/** 내리기 기록: 내려가기 직전 스냅샷(장학금 기본 필드만 있다). */
	function deleteBodyHtml(row) {
		let snapshot = null;
		try { snapshot = JSON.parse(row.beforeJson); } catch (ignored) { snapshot = null; }
		const view = snapshotSections(snapshot);
		const reason = /사유: (.*)$/s.exec(String(row.detail || ''));
		const head = reason ? '<div class="notice warn"><b>내린 사유:</b> ' + esc(reason[1]) + '</div>' : '';
		if (!view) return head + '<div class="notice neutral">내려가기 직전 내용이 기록되지 않았습니다.</div>';
		return head + '<p class="section-label">내려가기 직전 내용</p>' + sectionsHtml(view) +
			'<p class="field-help">내리기 기록에는 기본 정보만 남습니다(조건·서류·선발 일정은 그대로 장학금에 남아 있습니다).</p>' + rawToggle(row);
	}

	const scholarshipName = (row, id) => {
		const ref = (row.scholarships || {})[id];
		return '#' + id + ' ' + (ref && ref.title ? ref.title : '(제목을 찾지 못함)') + (ref && ref.deleted ? ' (내려짐)' : '');
	};

	/** 병합·중복 아님 기록: 유지 대상·내린 대상, 사유, 옮긴 참조 건수. */
	function mergeBodyHtml(row) {
		const info = parseMergeDetail(row.action, row.detail);
		if (!info) return '<div class="notice neutral">요약 문구를 읽지 못했습니다.</div><p class="text-sm">' + esc(row.detail || '') + '</p>';
		const merge = row.action === 'SCHOLARSHIP_MERGE';
		const pair = '<dl class="kv"><dt>유지 대상</dt><dd>' + scholarshipLinkHtml(info.keepId, scholarshipName(row, info.keepId)) + '</dd>' +
			'<dt>' + (merge ? '내린 대상' : '다른 쪽') + '</dt><dd>' + scholarshipLinkHtml(info.dropId, scholarshipName(row, info.dropId)) + '</dd>' +
			(info.candidateId ? '<dt>후보</dt><dd>#' + info.candidateId + '</dd>' : '') +
			(info.bulk ? '<dt>처리 방식</dt><dd>일괄 처리 ' + info.bulk.total + '건 중 ' + info.bulk.position + '번째</dd>' : '') + '</dl>';
		const note = !merge ? '<div class="notice neutral"><b>중복이 아닌 근거:</b> ' + esc(info.note || '(사유 없음)') + '</div>' : '';
		const movedRows = (info.moved || []).filter(m => m.count > 0);
		const moved = merge ? '<p class="section-label">옮기거나 정리한 데이터</p>' + (movedRows.length
			? '<ul class="moved-list">' + movedRows.map(m => '<li><span>' + esc(m.label) + '</span><b>' + m.count.toLocaleString('ko-KR') + '건</b></li>').join('') + '</ul>'
			: '<p class="text-muted text-sm">옮긴 사용자 데이터가 없습니다' + ((info.moved || []).length ? '' : '(요약에 건수가 남지 않았거나 잘렸습니다)') + '.</p>') : '';
		return pair + note + moved + '<p class="field-help">제목은 지금 값입니다. 병합 뒤 바뀌었을 수 있습니다.</p>';
	}

	const scholarshipLinkHtml = (id, text) => '<button type="button" class="btn-link" data-open-scholarship="' + id + '">' + esc(text) + '</button>';

	/** 기록 종류별로 어떤 상세를 열지. 버튼 문구와 본문을 함께 정한다. */
	function detailKind(row) {
		if (row.action === 'SCHOLARSHIP_CREATE') return {label: '등록 내용', body: createBodyHtml};
		if (row.action === 'SCHOLARSHIP_DELETE') return {label: row.beforeJson ? '내리기 직전 내용' : '내리기 내용', body: deleteBodyHtml};
		if (row.action === 'SCHOLARSHIP_MERGE') return {label: '병합 내용', body: mergeBodyHtml};
		if (row.action === 'SCHOLARSHIP_MERGE_REJECT') return {label: '처리 내용', body: mergeBodyHtml};
		if (row.beforeJson || row.afterJson) return {label: '전후 비교', body: diffBodyHtml};
		return null;
	}

	WC.audit = {
		// 순수 함수(테스트 대상)
		normalizeSnapshot, formatValue, diffSnapshots, listDiff, wordDiff, conditionSentence, documentSentence, timelineSentence,
		actionView, parseMergeDetail, snapshotSections, SYSTEM_FIELDS,
		// 화면
		diffBodyHtml, createBodyHtml, deleteBodyHtml, mergeBodyHtml, detailKind, scholarshipName
	};
})(typeof window !== 'undefined' ? window : globalThis);
