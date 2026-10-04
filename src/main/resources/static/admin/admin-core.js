/*
 * 관리자 콘솔 공통 부품. 화면별 코드(admin-console.js)는 여기 있는 것만 써서 화면을 그린다.
 *
 * - WC.api        : 관리자 API 호출(토큰·오류 해석)
 * - WC.ui         : 토스트, 모달, 위험 작업 확인, 버튼 로딩
 * - WC.view       : 로딩·빈 상태·에러 표시
 * - WC.fmt        : 시각(KST)·숫자 표시
 * - WC.L / label  : 영문 코드 → 한글 라벨
 *
 * 빌드 도구가 없는 정적 파일이라 모듈 대신 전역 WC 하나만 둔다.
 */
(() => {
	'use strict';

	const WC = window.WC = {};
	const TOKEN_KEY = 'wc_admin_token';

	/* ------------------------------------------------------------------ 문자열·DOM */

	const esc = value => value == null ? '' : String(value).replace(/[&<>"']/g,
		char => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[char]));
	const $ = id => document.getElementById(id);
	const safeUrl = value => /^https?:\/\//i.test(value || '') ? value : null;
	const textOrNull = value => value != null && String(value).trim() ? String(value).trim() : null;
	const tip = (text, extraClass = '') =>
		'<i class="tip ' + extraClass + '" tabindex="0" role="note" aria-label="' + esc(text) + '" data-tip="' + esc(text) + '">?</i>';
	const link = (url, label = '원문 열기', cls = 'btn btn-sm') => safeUrl(url)
		? '<a class="' + cls + '" target="_blank" rel="noopener noreferrer" href="' + esc(url) + '">' + esc(label) + ' ↗</a>' : '';

	Object.assign(WC, {esc, $, safeUrl, textOrNull, tip, link});

	/* ------------------------------------------------------------------ 시각 */

	/*
	 * 서버가 주는 LocalDateTime 은 시간대 표시가 없다. 운영 서버 JVM 은 UTC 라 그대로 보이면 9시간 어긋난다
	 * (QA 0.2). 서버 시계의 시간대를 한 번 재서(calibrate) 모든 "기록 시각"을 KST 로 바꿔 보여 준다.
	 * 모집 시작·마감처럼 공고에 적힌 날짜는 이미 한국 날짜라 바꾸지 않는다(fmt.biz).
	 */
	let serverOffsetMinutes = 0;
	const KST_PARTS = new Intl.DateTimeFormat('en-CA', {
		timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
		hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
	});
	const hasZone = value => /[zZ]$|[+-]\d\d:?\d\d$/.test(value);

	function toEpoch(value) {
		if (value == null || value === '') return null;
		if (typeof value === 'number') return value;
		const text = String(value);
		if (hasZone(text)) return Date.parse(text);
		const base = Date.parse(text.slice(0, 23) + 'Z');
		return Number.isNaN(base) ? null : base - serverOffsetMinutes * 60000;
	}
	function kstParts(epoch) {
		const parts = {};
		KST_PARTS.formatToParts(new Date(epoch)).forEach(part => parts[part.type] = part.value);
		return parts;
	}
	const fmt = {
		/** 서버의 "지금"(LocalDateTime) 하나로 서버 시계의 시간대를 잰다. 30분 단위로 반올림한다. */
		calibrate(serverLocalNow) {
			if (!serverLocalNow || hasZone(String(serverLocalNow))) return;
			const asUtc = Date.parse(String(serverLocalNow).slice(0, 23) + 'Z');
			if (Number.isNaN(asUtc)) return;
			serverOffsetMinutes = Math.round((asUtc - Date.now()) / 60000 / 30) * 30;
		},
		serverOffsetMinutes: () => serverOffsetMinutes,
		/** 기록 시각(생성·수정·수집·배치 등) → "2026-10-03 14:05" (KST) */
		ts(value, withSeconds = false) {
			const epoch = toEpoch(value);
			if (epoch == null || Number.isNaN(epoch)) return '-';
			const p = kstParts(epoch);
			return p.year + '-' + p.month + '-' + p.day + ' ' + p.hour + ':' + p.minute + (withSeconds ? ':' + p.second : '');
		},
		/** 단독으로 보여 줄 때는 KST 를 붙인다. */
		tsKst(value) { const text = fmt.ts(value); return text === '-' ? text : text + ' KST'; },
		/** 공고에 적힌 날짜(모집 시작·마감). 변환하지 않는다. */
		biz(value, dateOnly = false) {
			if (!value) return '-';
			const text = String(value).replace('T', ' ');
			return dateOnly ? text.slice(0, 10) : text.slice(0, 16);
		},
		period(start, end) {
			if (!start && !end) return '기간 없음';
			return fmt.biz(start) + ' ~ ' + fmt.biz(end);
		},
		/** "3분 전", "2일 전" */
		ago(value) {
			const epoch = toEpoch(value);
			if (epoch == null) return '';
			const minutes = Math.round((Date.now() - epoch) / 60000);
			if (minutes < 1) return '방금';
			if (minutes < 60) return minutes + '분 전';
			if (minutes < 60 * 24) return Math.floor(minutes / 60) + '시간 전';
			return Math.floor(minutes / 60 / 24) + '일 전';
		},
		ageDays(value) {
			const epoch = toEpoch(value);
			return epoch == null ? null : Math.floor((Date.now() - epoch) / 86400000);
		},
		/** KST 오늘 날짜 "YYYY-MM-DD" */
		todayKst() { const p = kstParts(Date.now()); return p.year + '-' + p.month + '-' + p.day; },
		/** KST 현재 벽시계 "YYYY-MM-DDTHH:mm" — 공고 날짜와 비교할 때 쓴다. */
		nowKstWall() { const p = kstParts(Date.now()); return p.year + '-' + p.month + '-' + p.day + 'T' + p.hour + ':' + p.minute; },
		num(value) { return value == null || value === '' ? '-' : Number(value).toLocaleString('ko-KR'); },
		won(value) { return value == null || value === '' ? '-' : Number(value).toLocaleString('ko-KR') + '원'; },
		bytes(value) {
			if (value == null) return '-';
			if (value >= 1024 * 1024 * 1024) return (value / 1024 / 1024 / 1024).toFixed(1) + ' GB';
			if (value >= 1024 * 1024) return (value / 1024 / 1024).toFixed(1) + ' MB';
			return Math.max(1, Math.round(value / 1024)) + ' KB';
		},
		duration(seconds) {
			const s = Math.max(0, Math.floor(seconds));
			const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), sec = s % 60;
			const pad = n => String(n).padStart(2, '0');
			return (h ? h + ':' + pad(m) : m) + ':' + pad(sec);
		},
		/** "2026-10-03T14:05" 로 input[type=datetime-local] 에 넣는다(공고 날짜용). */
		inputDate(value) { return value ? String(value).slice(0, 16) : ''; }
	};
	WC.fmt = fmt;

	/* ------------------------------------------------------------------ 라벨 */

	const L = {
		recruitment: {UPCOMING: '모집 예정', OPEN: '모집 중', ALWAYS_OPEN: '상시모집', CLOSED: '마감'},
		parse: {PENDING: '파싱 대기', PARSED: '파싱 완료', FAILED: '파싱 실패', SKIPPED: '건너뜀', IMAGE_ONLY: '이미지만 있음'},
		scholarshipType: {INTERNAL: '교내', EXTERNAL: '교외', WORK_STUDY: '근로'},
		conditionType: {
			UNIVERSITY_TYPE: '대학 유형', MAJOR_FIELD: '전공 계열', GRADE_LEVEL: '학년·학기', ACADEMIC_CRITERIA: '성적',
			INCOME_CRITERIA: '소득 분위', REGION_RESIDENCY: '거주 지역', SPECIFIC_QUALIFICATION: '특정 자격',
			RESTRICTION: '제한 사항', FINANCIAL_AID_TYPE: '학자금 지원 유형', RECOMMENDATION_REQUIRED: '추천 필요'
		},
		necessity: {REQUIRED: '필수 자격', PREFERRED: '우대'},
		operator: {EQ: '같음', IN: '다음 중 하나', GTE: '이상', LTE: '이하', BETWEEN: '범위'},
		channel: {ONLINE: '온라인', EMAIL: '이메일', POST: '우편', VISIT: '방문', FAX: '팩스', MIXED: '여러 방법', THIRD_PARTY: '외부 기관 경유'},
		requirement: {REQUIRED: '필수', CONDITIONAL: '조건부', NOT_REQUIRED: '없음'},
		noticeKind: {RECRUITMENT: '모집 공고', RESULT: '선발 결과 안내', GUIDE: '일반 안내', NOT_SCHOLARSHIP: '장학금 아님'},
		reportReason: {
			ALREADY_CLOSED: '이미 마감', WRONG_INFO: '정보 오류', WRONG_CONDITION: '조건 오류', DUPLICATE: '중복', OTHER: '기타',
			WRONG_DEADLINE: '마감일 오류(이전 선택지)', WRONG_AMOUNT: '금액 오류(이전 선택지)', BROKEN_LINK: '링크 오류(이전 선택지)'
		},
		inquiryType: {
			POSTER_IMAGE_TAKEDOWN: '포스터 게시 중단', SCHOLARSHIP_TAKEDOWN: '공고 게시 중단',
			COPYRIGHT_INFRINGEMENT: '저작권 침해', INFORMATION_CORRECTION: '정보 정정', OTHER: '기타'
		},
		handle: {PENDING: '처리 대기', RESOLVED: '해결', REJECTED: '반려'},
		mergeStatus: {PENDING: '승인 대기', REJECTED: '반려됨', MERGED: '병합됨'},
		mergeOrigin: {LLM: '자동 탐지', MANUAL: '관리자 수기'},
		jobStatus: {RUNNING: '실행 중', SUCCEEDED: '성공', WARNING: '부분 실패', PARTIAL_FAILURE: '부분 실패', FAILED: '실패'},
		jobType: {DAILY_SCHOLARSHIP_PIPELINE: '일일 수집 파이프라인'},
		trigger: {SCHEDULED: '자동(매일)', MANUAL: '수동'},
		targetType: {RAW_SCHOLARSHIP: '원문', SCHOLARSHIP: '장학금', SOURCE: '출처', GROUP: '묶음', STEP: '단계'},
		anomaly: {
			EMPTY_TITLE: '제목 없음', MISSING_PROVIDER: '기관 없음', DATE_REVERSED: '기간 역전',
			OPEN_BUT_ENDED: '마감 지난 모집 중', MISSING_LINK: '링크 없음', MISSING_CONDITION: '조건 없음'
		},
		deleteKind: {ADMIN: '관리자가 내림', MERGE: '병합으로 내림', SYSTEM: '수집 배치가 내림'},
		action: {
			EXCEL_IMPORT: '엑셀 일괄 수정', SCHOLARSHIP_CREATE: '장학금 등록', SCHOLARSHIP_UPDATE: '장학금 수정',
			SCHOLARSHIP_AGGREGATE_UPDATE: '통합 수정', SCHOLARSHIP_IMAGE_UPDATE: '이미지 등록·교체',
			MERGE_CANDIDATE_MANUAL_CREATE: '수기 중복 후보 생성', SCHOLARSHIP_DELETE: '내리기', SCHOLARSHIP_RESTORE: '복원',
			REPORT_RESOLVE: '신고 처리', CONTENT_INQUIRY_RESOLVE: '문의 처리', SYNC_TRIGGER: '공공데이터 수동 동기화',
			COLLECT_TRIGGER: '수동 수집', CONDITION_EXTRACT_TRIGGER: '조건 추출 실행', CONDITION_REF_BACKFILL: '조건 참조 채우기',
			ENRICH_TRIGGER: '자동 보완 실행', MERGE_DETECT_TRIGGER: '중복 탐지 실행', SCHOLARSHIP_MERGE: '병합 승인',
			SCHOLARSHIP_MERGE_REJECT: '중복 아님(반려)', MERGE_CANDIDATE_REOPEN: '반려 취소', AUDIT_RESTORE: '감사 복구'
		}
	};
	const label = (group, code, fallback = '-') => code == null || code === '' ? fallback : ((L[group] || {})[code] || code);
	const TONES = {
		recruitment: {OPEN: 'b-success', UPCOMING: 'b-info', ALWAYS_OPEN: 'b-brand', CLOSED: ''},
		parse: {PARSED: 'b-success', PENDING: 'b-info', FAILED: 'b-danger', SKIPPED: 'b-warning', IMAGE_ONLY: 'b-warning'},
		handle: {PENDING: 'b-warning', RESOLVED: 'b-success', REJECTED: ''},
		mergeStatus: {PENDING: 'b-warning', REJECTED: '', MERGED: 'b-success'},
		mergeOrigin: {LLM: 'b-info', MANUAL: 'b-brand'},
		jobStatus: {RUNNING: 'b-info', SUCCEEDED: 'b-success', WARNING: 'b-warning', PARTIAL_FAILURE: 'b-warning', FAILED: 'b-danger'}
	};
	/** 코드 배지. 한글 라벨을 보여 주고 원래 코드는 마우스를 올리면 보인다. */
	const badge = (group, code, tone) => code == null || code === '' ? '<span class="text-muted">-</span>'
		: '<span class="badge ' + (tone || (TONES[group] || {})[code] || '') + '" title="' + esc(code) + '">' + esc(label(group, code)) + '</span>';
	const options = (group, current, blank) => (blank != null ? '<option value="">' + esc(blank) + '</option>' : '') +
		Object.keys(L[group]).map(code => '<option value="' + code + '"' + (code === (current || '') ? ' selected' : '') + '>' +
			esc(L[group][code]) + '</option>').join('');
	Object.assign(WC, {L, label, badge, options});

	/* ------------------------------------------------------------------ 오류 */

	class ApiError extends Error {
		constructor({status = 0, message, data = null, network = false, path}) {
			super(message || (network ? '서버에 연결하지 못했습니다.' : '요청을 처리하지 못했습니다.'));
			Object.assign(this, {status, data, network, path});
		}
	}

	/** 오류를 "원인"과 "다음에 할 일"로 나눈다. 화면 에러 상태·토스트·모달이 모두 이걸 쓴다. */
	function describeError(error) {
		if (!(error instanceof ApiError)) {
			return {cause: error && error.message ? error.message : String(error), next: '입력을 확인하고 다시 시도하세요.'};
		}
		const msg = error.message;
		if (error.network) return {cause: '서버에 연결하지 못했습니다.', next: '인터넷 연결을 확인하고 다시 시도하세요. 계속되면 다른 관리자에게 서버 상태를 확인해 달라고 알려 주세요.'};
		switch (error.status) {
			case 400: return {cause: msg, next: '표시된 입력값을 고친 뒤 다시 시도하세요.'};
			case 401: return {cause: msg || '로그인이 만료되었습니다.', next: '다시 로그인하세요.'};
			case 403: return {cause: '이 작업을 할 권한이 없습니다.', next: 'ADMIN 권한이 있는 관리자 계정으로 로그인했는지 확인하세요.'};
			case 404: return {cause: msg, next: '목록을 새로고침하세요. 다른 관리자가 먼저 처리했거나 삭제됐을 수 있습니다.'};
			case 409: return {cause: msg, next: '목록을 새로고침해 최신 상태를 확인한 뒤 다시 시도하세요.'};
			case 413: return {cause: msg || '파일이 너무 큽니다.', next: '5MB 이하로 줄여서 다시 올리세요.'};
			case 429: return {cause: msg, next: '잠시 기다린 뒤 다시 시도하세요.'};
			case 502: case 503: case 504:
				return {cause: msg || '서버 또는 외부 연동이 응답하지 않습니다.', next: '잠시 후 다시 시도하세요. 반복되면 [시스템 상태] 로그를 확인하세요.'};
			default:
				if (error.status >= 500) {
					return {cause: msg || '서버 오류가 발생했습니다(HTTP ' + error.status + ').',
						next: '잠시 후 다시 시도하세요. 반복되면 [시스템 상태] 로그에서 같은 시각의 ERROR 를 찾아 개발 담당에게 전달하세요.'};
				}
				return {cause: msg, next: '다시 시도하세요.'};
		}
	}
	const errorText = error => { const d = describeError(error); return d.cause + '\n→ ' + d.next; };
	const errorNotice = error => {
		const d = describeError(error);
		return '<div class="notice danger"><b>' + esc(d.cause) + '</b><br>' + esc(d.next) + '</div>';
	};
	Object.assign(WC, {ApiError, describeError, errorText, errorNotice});

	/* ------------------------------------------------------------------ 인증·API */

	const auth = {
		token: sessionStorage.getItem(TOKEN_KEY),
		listeners: [],
		setToken(value) {
			if (!value || value === auth.token) return;
			auth.token = value;
			try { sessionStorage.setItem(TOKEN_KEY, value); } catch (ignored) { /* 저장 못 해도 메모리 토큰으로 계속 쓴다 */ }
			auth.listeners.forEach(listener => listener(value));
		},
		onToken(listener) { auth.listeners.push(listener); },
		/** 401 을 받았을 때. 기본은 로그인 화면으로 보낸다(세션 모듈이 재로그인 모달로 바꾼다). */
		async handleUnauthorized() {
			location.replace('/admin/login.html');
			throw new ApiError({status: 401, message: '로그인이 만료되었습니다.'});
		}
	};
	WC.auth = auth;

	/**
	 * 관리자 API 호출. 성공하면 ApiResponse.data 를, 실패하면 ApiError 를 던진다.
	 * options: method, body(객체 → JSON), form(FormData), background(세션 연장 안 함), raw(Response 반환)
	 */
	async function api(path, options = {}) {
		const {method = 'GET', body, form, background = false, raw = false, retried = false} = options;
		const headers = {Authorization: 'Bearer ' + auth.token};
		if (background) headers['X-Admin-Background'] = 'true';
		if (body !== undefined) headers['Content-Type'] = 'application/json';
		let response;
		try {
			response = await fetch(path, {method, headers, body: form || (body !== undefined ? JSON.stringify(body) : undefined)});
		} catch (error) {
			throw new ApiError({network: true, path});
		}
		// 서버는 관리자 활동마다 세션을 늘린 새 토큰을 이 헤더로 내려 준다.
		const renewed = response.headers.get('X-Admin-Access-Token');
		if (renewed) auth.setToken(renewed);
		if (response.status === 401 && !retried) {
			await auth.handleUnauthorized(path);
			return api(path, {...options, retried: true});
		}
		if (raw && response.ok) return response;
		const json = await response.json().catch(() => null);
		if (!response.ok || !json || json.success === false) {
			throw new ApiError({status: response.status, message: json && json.message, data: json && json.data, path});
		}
		return json.data;
	}
	WC.api = api;

	/** 인증이 필요한 파일 내려받기(엑셀 등). */
	WC.download = async (path, filename) => {
		const response = await api(path, {raw: true});
		const blob = await response.blob();
		const url = URL.createObjectURL(blob);
		const anchor = document.createElement('a');
		anchor.href = url; anchor.download = filename;
		document.body.appendChild(anchor); anchor.click(); anchor.remove();
		setTimeout(() => URL.revokeObjectURL(url), 1000);
	};

	/* ------------------------------------------------------------------ 토스트 */

	function toast(message, kind = 'success', timeout) {
		let host = $('toasts');
		if (!host) { host = document.createElement('div'); host.id = 'toasts'; host.className = 'toasts'; host.setAttribute('aria-live', 'polite'); document.body.appendChild(host); }
		const item = document.createElement('div');
		item.className = 'toast ' + kind;
		item.setAttribute('role', kind === 'error' ? 'alert' : 'status');
		item.innerHTML = '<span class="toast-msg"></span><button type="button" aria-label="닫기">×</button>';
		item.querySelector('.toast-msg').textContent = message;
		const remove = () => item.remove();
		item.querySelector('button').onclick = remove;
		host.appendChild(item);
		setTimeout(remove, timeout || (kind === 'error' ? 9000 : kind === 'warn' ? 7000 : 3500));
	}

	/* ------------------------------------------------------------------ 버튼 로딩 */

	/** 쓰기 버튼: 요청 중 비활성 + 스피너. 이미 처리 중이면 다시 누른 것을 무시한다. */
	async function busy(button, task, busyLabel) {
		if (!button) return task();
		if (button.getAttribute('aria-busy') === 'true') return undefined;
		const html = button.innerHTML, wasDisabled = button.disabled;
		button.setAttribute('aria-busy', 'true');
		button.disabled = true;
		button.innerHTML = '<span class="spinner" aria-hidden="true"></span>' + esc(busyLabel || button.textContent.trim());
		try {
			return await task();
		} finally {
			button.removeAttribute('aria-busy');
			button.disabled = wasDisabled;
			button.innerHTML = html;
		}
	}

	/* ------------------------------------------------------------------ 모달 */

	/**
	 * 콘솔 자체 모달. 브라우저 기본 prompt/confirm/alert 를 쓰지 않는다.
	 * onConfirm 이 던지면 모달을 닫지 않고 안에 오류를 보여 준다(입력 유지). false 를 돌려주면 그대로 둔다.
	 * 결과: onConfirm 의 반환값, 취소하면 null.
	 */
	function modal(opts) {
		return new Promise(resolve => {
			const dialog = document.createElement('dialog');
			dialog.className = 'modal ' + (opts.size || '');
			dialog.innerHTML = '<div class="modal-head"><div><h2></h2><div class="modal-sub"></div></div>' +
				'<button type="button" class="modal-x" aria-label="닫기">×</button></div>' +
				'<div class="modal-body"><div class="modal-error" role="alert"></div><div class="modal-content"></div></div>' +
				'<div class="modal-foot"><span class="foot-note"></span><button type="button" class="btn" data-cancel></button>' +
				'<button type="button" class="btn" data-ok></button></div>';
			const q = selector => dialog.querySelector(selector);
			const content = q('.modal-content'), okButton = q('[data-ok]'), cancelButton = q('[data-cancel]');
			const errorBox = q('.modal-error');
			q('h2').textContent = opts.title || '';
			if (opts.subtitle) q('.modal-sub').textContent = opts.subtitle; else q('.modal-sub').remove();
			if (opts.footNote) q('.foot-note').innerHTML = opts.footNote;
			if (typeof opts.body === 'string') content.innerHTML = opts.body; else if (opts.body) content.appendChild(opts.body);
			cancelButton.textContent = opts.cancelLabel || (opts.onConfirm ? '취소' : '닫기');
			if (opts.onConfirm) {
				okButton.textContent = opts.confirmLabel || '확인';
				okButton.className = 'btn ' + (opts.kind === 'danger' ? 'btn-danger' : 'btn-primary');
			} else {
				okButton.remove();
			}
			let working = false, settled = false;
			const finish = value => {
				if (settled) return;
				settled = true;
				dialog.close(); dialog.remove();
				if (opts.onClose) opts.onClose(value);
				resolve(value);
			};
			const ctx = {
				root: content, dialog, okButton,
				close: finish,
				setError(error) { errorBox.innerHTML = error ? (typeof error === 'string' ? '<div class="notice danger">' + esc(error) + '</div>' : errorNotice(error)) : ''; },
				validate() {
					if (!opts.onConfirm) return;
					okButton.disabled = working || (opts.valid ? !opts.valid(content, ctx) : false);
				}
			};
			content.addEventListener('input', ctx.validate);
			content.addEventListener('change', ctx.validate);
			const cancel = () => { if (!working) finish(null); };
			cancelButton.onclick = cancel;
			q('.modal-x').onclick = cancel;
			dialog.addEventListener('cancel', event => { event.preventDefault(); cancel(); });
			okButton.onclick = async () => {
				if (working || okButton.disabled) return;
				working = true;
				const okHtml = okButton.innerHTML;
				okButton.setAttribute('aria-busy', 'true');
				okButton.disabled = true; cancelButton.disabled = true;
				okButton.innerHTML = '<span class="spinner" aria-hidden="true"></span>처리 중…';
				ctx.setError(null);
				try {
					const result = await opts.onConfirm(content, ctx);
					if (result === false) return;
					finish(result === undefined ? true : result);
				} catch (error) {
					ctx.setError(error);
					errorBox.scrollIntoView({block: 'nearest'});
				} finally {
					working = false;
					if (!settled) {
						okButton.removeAttribute('aria-busy');
						okButton.innerHTML = okHtml; cancelButton.disabled = false; ctx.validate();
					}
				}
			};
			document.body.appendChild(dialog);
			dialog.showModal();
			if (opts.onOpen) opts.onOpen(content, ctx);
			ctx.validate();
			const first = content.querySelector('[autofocus]') ||
				content.querySelector('textarea, input:not([type=hidden]):not([type=checkbox]):not([type=radio]), select');
			(first || cancelButton).focus();
		});
	}

	const REVERSIBLE = {
		yes: ['yes', '되돌릴 수 있습니다'],
		partial: ['partial', '일부만 되돌릴 수 있습니다'],
		no: ['no', '되돌릴 수 없습니다']
	};

	/**
	 * 위험 작업 확인. 대상(ID·제목), 변경 요약, 되돌릴 수 있는지를 반드시 보여 준다.
	 * reason: {label, required, placeholder, help, maxLength} — 기본값 없이 빈 칸으로 시작하고,
	 * required 면 비어 있는 동안 확인 버튼을 막는다. onConfirm(reason, root, ctx)
	 */
	function confirmAction(opts) {
		const targets = (opts.targets || []).map(target => '<div class="target-box"><span class="target-id">' + esc(target.id) +
			(target.meta ? ' · ' + esc(target.meta) : '') + '</span><span class="target-title">' + esc(target.title || '(제목 없음)') + '</span></div>').join('');
		const summary = opts.summary && opts.summary.length
			? '<p class="section-label">변경 내용</p><ul class="summary-list">' + opts.summary.map(line => '<li>' + line + '</li>').join('') + '</ul>' : '';
		const rev = REVERSIBLE[opts.reversible || 'no'];
		const revert = '<div class="revert ' + rev[0] + '"><b>' + rev[1] + '.</b> ' + (opts.reversibleText || '') + '</div>';
		const reason = opts.reason ? '<div class="form-field"><label for="wc-reason">' + esc(opts.reason.label || '사유') +
			(opts.reason.required ? ' <span class="req">*</span>' : ' <span class="text-muted">(선택)</span>') + '</label>' +
			'<textarea id="wc-reason" class="textarea" data-reason maxlength="' + (opts.reason.maxLength || 300) + '" placeholder="' +
			esc(opts.reason.placeholder || '') + '"></textarea>' + (opts.reason.help ? '<span class="field-help">' + esc(opts.reason.help) + '</span>' : '') +
			'</div>' : '';
		return modal({
			title: opts.title, subtitle: opts.subtitle, size: opts.size, kind: opts.kind || 'danger',
			confirmLabel: opts.confirmLabel, footNote: opts.footNote,
			body: targets + summary + (opts.extraHtml || '') + revert + reason,
			onOpen: opts.onOpen,
			valid: root => {
				const box = root.querySelector('[data-reason]');
				if (opts.reason && opts.reason.required && !(box && box.value.trim())) return false;
				return opts.valid ? opts.valid(root) : true;
			},
			onConfirm: (root, ctx) => {
				const box = root.querySelector('[data-reason]');
				return opts.onConfirm(box ? box.value.trim() : null, root, ctx);
			}
		});
	}

	/** 단순 안내(확인 버튼 하나). */
	const notify = (title, html) => modal({title, body: html});

	WC.ui = {toast, busy, modal, confirmAction, notify};

	/* ------------------------------------------------------------------ 상태 표시 */

	const view = {
		loadingHtml: (text = '불러오는 중…') => '<div class="state compact"><span class="spinner" aria-hidden="true"></span>' + esc(text) + '</div>',
		emptyHtml: (title, hint) => '<div class="state compact"><b>' + esc(title) + '</b>' + (hint ? '<div>' + esc(hint) + '</div>' : '') + '</div>',
		errorHtml(error, what = '데이터를') {
			const d = describeError(error);
			return '<div class="state compact state-error" role="alert"><b>' + esc(what) + ' 불러오지 못했습니다</b><div>원인: ' + esc(d.cause) +
				'</div><div class="state-next">다음 행동: ' + esc(d.next) + '</div><button type="button" class="btn btn-sm" data-retry>다시 시도</button></div>';
		},
		row: (colspan, html) => '<tr><td colspan="' + colspan + '">' + html + '</td></tr>',
		/**
		 * 영역 하나를 불러온다. 처음엔 스피너, 이미 내용이 있으면 흐리게 표시한다. task 가 돌려준 HTML 을 넣고,
		 * 실패하면 원인·다음 행동·다시 시도 버튼을 보여 준다. 실패해도 던지지 않고 false 를 돌려준다.
		 */
		async load(target, task, {colspan, what, keep} = {}) {
			if (!target) return false;
			const wrap = html => colspan ? view.row(colspan, html) : html;
			const hasContent = keep !== false && target.dataset.loaded === 'true';
			if (hasContent) target.classList.add('is-loading'); else target.innerHTML = wrap(view.loadingHtml());
			target.setAttribute('aria-busy', 'true');
			try {
				const html = await task();
				if (typeof html === 'string') target.innerHTML = html;
				target.dataset.loaded = 'true';
				return true;
			} catch (error) {
				target.innerHTML = wrap(view.errorHtml(error, what));
				target.dataset.loaded = 'false';
				const retry = target.querySelector('[data-retry]');
				if (retry) retry.onclick = () => view.load(target, task, {colspan, what});
				return false;
			} finally {
				target.classList.remove('is-loading');
				target.removeAttribute('aria-busy');
			}
		}
	};
	WC.view = view;

	/* ------------------------------------------------------------------ 세션 */

	/*
	 * 관리자 세션: 활동하면 서버가 새 토큰(X-Admin-Access-Token)을 내려 30분씩 늘어나고, 로그인 후 8시간이 최대다.
	 * 우측 상단에 남은 시간과 [연장]을 보여 주고, 5분 전부터 경고한다. 401 이 나면 페이지를 옮기지 않고
	 * 재로그인 모달을 띄운 뒤 원래 요청을 다시 보낸다(열려 있던 모달·입력은 그대로 남는다).
	 */
	const SESSION_MAX_KEY = 'wc_admin_session_max';
	const LOGIN_ID_KEY = 'wc_admin_login_id';
	const NAME_KEY = 'wc_admin_name';
	const WARN_SECONDS = 5 * 60;
	const session = {expiresAt: null, maxExpiresAt: null, expiredPrompted: false};

	const epochMs = value => {
		if (value == null || value === '') return null;
		if (typeof value === 'number') return value < 1e12 ? value * 1000 : value;
		if (/^\d+(\.\d+)?$/.test(String(value))) return epochMs(Number(value));
		const parsed = Date.parse(value);
		return Number.isNaN(parsed) ? null : parsed;
	};

	function decodeToken(token) {
		try {
			const part = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
			return JSON.parse(atob(part + '='.repeat((4 - part.length % 4) % 4)));
		} catch (ignored) {
			return null;
		}
	}

	function readToken(token) {
		const claims = token ? decodeToken(token) : null;
		if (claims && claims.exp) session.expiresAt = claims.exp * 1000;
		const storedMax = epochMs(sessionStorage.getItem(SESSION_MAX_KEY));
		if (storedMax) session.maxExpiresAt = storedMax;
		else if (claims && claims.ast != null && epochMs(claims.ast)) session.maxExpiresAt = epochMs(claims.ast) + 8 * 3600 * 1000;
		session.expiredPrompted = false;
		renderSession();
	}

	/** 로그인·연장·남은 시간 응답(accessToken, expiresAt, remainingSeconds, sessionMaxExpiresAt)을 반영한다. */
	function applySession(data) {
		if (!data) return;
		if (data.accessToken) auth.setToken(data.accessToken);
		if (data.expiresAt) session.expiresAt = epochMs(data.expiresAt);
		else if (data.remainingSeconds != null) session.expiresAt = Date.now() + data.remainingSeconds * 1000;
		else if (data.expiresInSeconds != null) session.expiresAt = Date.now() + data.expiresInSeconds * 1000;
		if (data.sessionMaxExpiresAt) {
			session.maxExpiresAt = epochMs(data.sessionMaxExpiresAt);
			try { sessionStorage.setItem(SESSION_MAX_KEY, String(session.maxExpiresAt)); } catch (ignored) { /* 메모리 값으로 계속 */ }
		}
		session.expiredPrompted = false;
		renderSession();
	}

	const secondsLeft = () => session.expiresAt ? (session.expiresAt - Date.now()) / 1000 : null;
	/** 최대 사용 시간에 닿아 더 늘릴 수 없는지. */
	const atMaxLifetime = () => Boolean(session.maxExpiresAt && session.expiresAt && session.maxExpiresAt - session.expiresAt < 60 * 1000);

	function renderSession() {
		const box = $('session');
		if (!box) return;
		const left = secondsLeft();
		const button = $('sessionExtend'), banner = $('sessionBanner');
		if (left == null) { $('sessionLeft').textContent = '--:--'; return; }
		const capped = atMaxLifetime();
		$('sessionLeft').textContent = left > 0 ? fmt.duration(left) : '만료됨';
		box.className = 'session' + (left <= 0 ? ' danger' : left <= WARN_SECONDS ? ' warn' : '');
		if (button.getAttribute('aria-busy') !== 'true') {
			button.disabled = left <= 0 || capped;
			button.title = capped ? '로그인 후 최대 사용 시간(8시간)에 닿아 더 연장할 수 없습니다. 작업을 저장하고 다시 로그인하세요.'
				: '세션을 지금부터 30분으로 늘립니다.';
		}
		if (banner) {
			const show = left > 0 && left <= WARN_SECONDS;
			banner.hidden = !show;
			if (show) {
				$('sessionBannerText').textContent = capped
					? '로그인 후 최대 사용 시간이 ' + fmt.duration(left) + ' 뒤 끝납니다. 더 연장할 수 없으니 작업을 저장한 뒤 다시 로그인하세요.'
					: '세션이 ' + fmt.duration(left) + ' 뒤 만료됩니다. 계속 작업하려면 연장하세요.';
				$('sessionBannerExtend').hidden = capped;
			}
		}
		if (left <= 0 && !session.expiredPrompted) {
			session.expiredPrompted = true;
			relogin('세션이 만료되었습니다.').catch(() => {});
		}
	}

	async function extendSession(button) {
		await busy(button, async () => {
			try {
				applySession(await api('/api/v1/admin/auth/extend', {method: 'POST'}));
				toast('세션을 연장했습니다. 남은 시간 ' + fmt.duration(secondsLeft()));
			} catch (error) {
				toast(errorText(error), 'error');
			}
		}, '연장 중…');
	}

	function syncSession() {
		return api('/api/v1/admin/auth/session', {background: true}).then(applySession).catch(() => {});
	}

	/** 로그인 실패 응답(data: failedCount, maxFailures, remainingAttempts, lockMinutes, locked, lockScope, lockRemainingSeconds)을 문장으로. */
	function loginFailureText(status, message, data) {
		const d = data || {};
		if (d.locked || status === 429) {
			const wait = d.lockRemainingSeconds != null ? fmt.duration(d.lockRemainingSeconds) + ' 뒤에' : '잠시 뒤에';
			return (d.lockScope === 'IP' ? '이 네트워크(IP)에서 로그인 실패가 너무 많아' : '로그인에 ' + (d.maxFailures || 5) + '회 실패해') +
				' 로그인이 잠겼습니다. ' + wait + ' 다시 시도하세요.';
		}
		if (d.failedCount != null) {
			return '아이디 또는 비밀번호가 맞지 않습니다. ' + d.failedCount + '회 실패했습니다. ' + (d.maxFailures || 5) + '회 실패하면 ' +
				(d.lockMinutes || 15) + '분간 로그인이 잠깁니다' + (d.remainingAttempts != null ? ' (남은 시도 ' + d.remainingAttempts + '회).' : '.');
		}
		return message || '로그인하지 못했습니다(HTTP ' + status + ').';
	}

	async function loginRequest(loginId, password) {
		let response;
		try {
			response = await fetch('/api/v1/admin/auth/login', {method: 'POST', headers: {'Content-Type': 'application/json'},
				body: JSON.stringify({loginId, password})});
		} catch (error) {
			throw new ApiError({network: true});
		}
		const json = await response.json().catch(() => null);
		if (!response.ok || !json || json.success === false) {
			throw new Error(loginFailureText(response.status, json && json.message, json && json.data));
		}
		return json.data;
	}

	function rememberLogin(loginId, data) {
		try {
			sessionStorage.setItem(LOGIN_ID_KEY, loginId);
			if (data.name) sessionStorage.setItem(NAME_KEY, data.name);
		} catch (ignored) { /* 저장 못 해도 진행 */ }
		applySession(data);
	}

	let reloginPromise = null;
	/** 재로그인 모달. 여러 요청이 동시에 401 을 받아도 모달은 하나만 띄우고 모두 결과를 기다린다. */
	function relogin(reason) {
		if (reloginPromise) return reloginPromise;
		reloginPromise = (async () => {
			const savedId = sessionStorage.getItem(LOGIN_ID_KEY) || '';
			const body = '<div class="notice warn"><b>' + esc(reason) + '</b> 다시 로그인하면 하던 작업을 이어서 합니다. 입력 중인 내용은 지워지지 않습니다.</div>' +
				'<div class="form-field"><label for="reloginId">아이디</label><input id="reloginId" class="input" autocomplete="username" value="' + esc(savedId) + '"></div>' +
				'<div class="form-field" style="margin-top:var(--sp-3)"><label for="reloginPw">비밀번호</label>' +
				'<input id="reloginPw" class="input" type="password" autocomplete="current-password"' + (savedId ? ' autofocus' : '') + '></div>' +
				'<p class="field-help" style="margin-top:var(--sp-3)">[나중에]를 누르면 방금 작업은 보내지지 않습니다. 화면은 그대로 남습니다.</p>';
			const result = await modal({
				title: '다시 로그인', body, confirmLabel: '로그인하고 계속', cancelLabel: '나중에',
				valid: root => Boolean(root.querySelector('#reloginId').value.trim() && root.querySelector('#reloginPw').value),
				onOpen: (root, ctx) => root.querySelector('#reloginPw').addEventListener('keydown', event => {
					if (event.key === 'Enter' && !event.isComposing) { event.preventDefault(); ctx.okButton.click(); }
				}),
				onConfirm: async root => {
					const loginId = root.querySelector('#reloginId').value.trim();
					const password = root.querySelector('#reloginPw');
					try {
						rememberLogin(loginId, await loginRequest(loginId, password.value));
					} finally {
						password.value = '';
					}
					return true;
				}
			});
			if (!result) throw new ApiError({status: 401, message: '로그인하지 않아 요청을 보내지 못했습니다.'});
			const name = $('adminName');
			if (name) name.textContent = sessionStorage.getItem(NAME_KEY) || name.textContent;
			toast('다시 로그인했습니다. 하던 작업을 이어서 보냅니다.');
		})().finally(() => { reloginPromise = null; });
		return reloginPromise;
	}

	auth.handleUnauthorized = () => relogin('로그인 세션이 만료되었습니다.');
	auth.onToken(readToken);

	WC.session = {
		/** 콘솔 화면이 시작할 때 부른다. */
		mount() {
			readToken(auth.token);
			const button = $('sessionExtend');
			if (button) button.onclick = () => extendSession(button);
			const bannerButton = $('sessionBannerExtend');
			if (bannerButton) bannerButton.onclick = () => extendSession(bannerButton);
			setInterval(renderSession, 1000);
			syncSession();
			setInterval(() => { if (!document.hidden) syncSession(); }, 5 * 60 * 1000);
			document.addEventListener('visibilitychange', () => { if (!document.hidden) syncSession(); });
		},
		extend: extendSession,
		relogin,
		loginFailureText,
		state: session
	};
})();
