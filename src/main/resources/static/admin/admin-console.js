/*
 * 관리자 콘솔 화면. 공통 부품(admin-core.js 의 WC)만 써서 화면을 그린다.
 *
 * 구성: 공통 도우미 → 배지·대시보드 → 상세 패널·수정 모달 → 화면별 로더 → 쓰기 작업 → 이벤트 연결·시작
 */
(() => {
	'use strict';

	const {api, ui, view, fmt, esc, $, badge, label, options, link, safeUrl, textOrNull, tip, L} = WC;
	if (!WC.auth.token) {
		location.replace('/admin/login.html');
		return;
	}

	const PAGE_SIZE = 20;
	const pageNo = {intake: 0, failures: 0, anomaly: 0, scholarship: 0, always: 0, duplicate: 0, image: 0,
		report: 0, inquiry: 0, jobs: 0};
	let currentPage = null;

	/* ================================================================== 공통 도우미 */

	const qs = (base, params) => {
		const search = new URLSearchParams();
		Object.entries(params).forEach(([key, value]) => {
			if (value !== null && value !== undefined && value !== '') search.set(key, value);
		});
		const text = search.toString();
		return text ? base + '?' + text : base;
	};
	const val = id => $(id).value.trim();
	const cells = list => list.map(html => '<td>' + html + '</td>').join('');
	const scholarshipLink = (id, title) => '<button type="button" class="btn-link" data-open-scholarship="' + id + '">' +
		esc(title || '(제목 없음)') + '</button>';

	/** Spring Page 응답으로 페이지 이동 버튼을 그린다. */
	function renderPager(id, page, key, loader) {
		const target = $(id);
		const total = Number(page.totalElements || 0);
		const totalPages = Math.max(page.totalPages || Math.ceil(total / PAGE_SIZE) || 0, 1);
		const number = page.number || 0;
		if (!total) { target.innerHTML = ''; return; }
		target.innerHTML = '<button type="button" class="btn btn-sm" data-prev' + (number <= 0 ? ' disabled' : '') + '>이전</button>' +
			'<span>' + (number + 1) + ' / ' + totalPages + ' 페이지 · 총 ' + fmt.num(total) + '건</span>' +
			'<button type="button" class="btn btn-sm" data-next' + (number + 1 >= totalPages ? ' disabled' : '') + '>다음</button>';
		target.querySelector('[data-prev]').onclick = () => { pageNo[key] = Math.max(0, number - 1); loader(); };
		target.querySelector('[data-next]').onclick = () => { pageNo[key] = number + 1; loader(); };
	}

	const missingLabels = row => [['요약', row.hasSummary], ['금액', row.hasAmount], ['링크', row.hasHomepageUrl],
		['포스터', row.hasPoster]].filter(item => !item[1]).map(item => item[0]);
	const missingHtml = row => {
		const missing = missingLabels(row);
		return missing.length ? '<div class="badges">' + missing.map(name => '<span class="badge b-warning">' + name + '</span>').join('') + '</div>'
			: '<span class="text-success text-sm">없음</span>';
	};
	const sourceHtml = source => '<span class="mono" title="수집 출처 코드">' + esc(source || 'MANUAL') + '</span>';
	const deletedBadge = row => '<span class="badge b-danger" title="' + esc(label('deleteKind', row.deleteKind, '')) + '">내려짐</span>';

	/* ================================================================== 배지·대시보드 */

	const counts = {};
	function setCount(key, value, warn = false) {
		counts[key] = value;
		document.querySelectorAll('[data-count="' + key + '"],[data-queue="' + key + '"]').forEach(el => {
			el.textContent = value == null ? '' : fmt.num(value);
			if (el.classList.contains('count')) el.classList.toggle('warn', Boolean(warn && value));
		});
	}

	const settled = result => result.status === 'fulfilled' ? result.value : null;
	const RECENT_ATTENTION_DAYS = 3;
	const needsAttention = status => ['WARNING', 'PARTIAL_FAILURE', 'FAILED'].includes(status);

	/**
	 * 메뉴 배지에 쓰는 숫자를 한 번에 다시 센다. 쓰기 작업 뒤와 3분마다 부른다.
	 * background 요청은 서버 세션을 늘리지 않는다(자리를 비운 사이 세션이 계속 연장되지 않게).
	 */
	async function refreshCounts({background = true} = {}) {
		const opt = {background};
		const results = await Promise.allSettled([
			api('/api/v1/scholarships/admin/overview', opt),
			api(qs('/api/v1/scholarships/admin/failures', {page: 0, size: 1}), opt),
			api(qs('/api/v1/scholarships/admin/anomalies', {page: 0, size: 1}), opt),
			api(qs('/api/v1/scholarships/merge/candidates', {status: 'PENDING', page: 0, size: 1}), opt),
			api(qs('/api/v1/scholarships/reports', {status: 'PENDING', page: 0, size: 1}), opt),
			api(qs('/api/v1/admin/content-inquiries', {status: 'PENDING', page: 0, size: 1}), opt),
			api(qs('/api/v1/admin/jobs', {page: 0, size: 20}), opt)
		]);
		const [overview, failures, anomalies, merges, reports, inquiries, jobs] = results.map(settled);
		if (overview) {
			setCount('intake', overview.raw.pending);
			setCount('always', overview.scholarship.alwaysOpen);
		}
		if (failures) setCount('failures', failures.totalElements);
		if (anomalies) setCount('anomaly', anomalies.totalElements);
		if (merges) setCount('duplicate', merges.totalCount);
		if (reports || inquiries) {
			setCount('reports', (reports ? reports.totalElements : 0) + (inquiries ? inquiries.totalElements : 0));
			if ($('reportTabCount') && reports) $('reportTabCount').textContent = fmt.num(reports.totalElements);
			if ($('inquiryTabCount') && inquiries) $('inquiryTabCount').textContent = fmt.num(inquiries.totalElements);
		}
		if (jobs) {
			const recent = jobs.content.filter(job => needsAttention(job.status) &&
				fmt.ageDays(job.startedAt) != null && fmt.ageDays(job.startedAt) < RECENT_ATTENTION_DAYS);
			setCount('batches', recent.length, true);
		}
		return {overview, failures, anomalies, merges, reports, inquiries, jobs, error: results.find(r => r.status === 'rejected')};
	}

	let dashboardStale = true;
	async function loadDashboard() {
		dashboardStale = false;
		const stats = $('dashStats');
		stats.classList.add('is-loading');
		const [data, recent] = await Promise.all([
			refreshCounts({background: false}),
			api('/api/v1/scholarships/admin/recent?size=10').catch(error => ({error}))
		]);
		stats.classList.remove('is-loading');
		if (data.overview) {
			const s = data.overview.scholarship;
			const statValues = {createdToday: s.createdToday, total: s.total, open: s.open, upcoming: s.upcoming, softDeleted: s.softDeleted,
				failures: data.failures ? data.failures.totalElements : null};
			Object.entries(statValues).forEach(([key, value]) => {
				const el = document.querySelector('[data-stat="' + key + '"]');
				if (el) el.textContent = fmt.num(value);
			});
			document.querySelector('[data-stat-sub="total"]').textContent = '노출 ' + fmt.num(s.active) + ' · 마감 ' + fmt.num(s.closed);
			$('dashUpdated').textContent = '마지막 수집 동기화 ' + fmt.tsKst(s.lastSyncedAt);
			renderQuality(data.overview.sourceQuality || []);
		} else {
			$('dashQuality').innerHTML = view.row(6, view.errorHtml(data.error ? data.error.reason : new Error('현황을 받지 못했습니다.'), '현황을'));
			bindRetry($('dashQuality'), loadDashboard);
		}
		renderDashboardRun(data.jobs);
		renderRecent(recent);
	}

	function bindRetry(target, loader) {
		const retry = target.querySelector('[data-retry]');
		if (retry) retry.onclick = () => loader();
	}

	function renderDashboardRun(jobs) {
		const target = $('dashRun');
		const running = jobs && jobs.content.find(job => job.status === 'RUNNING');
		$('dashRunning').hidden = !running;
		if (running) {
			$('dashRunning').innerHTML = '<b>수집 배치가 실행 중입니다</b> (시작 ' + esc(fmt.tsKst(running.startedAt)) +
				'). 위 숫자는 배치가 끝나기 전 값일 수 있습니다. 끝난 뒤 [새로고침]하세요.';
		}
		if (!jobs) { target.innerHTML = view.emptyHtml('배치 이력을 불러오지 못했습니다.', '[배치 실행 이력] 화면에서 다시 확인하세요.'); return; }
		const latest = jobs.content[0];
		if (!latest) { target.innerHTML = view.emptyHtml('기록된 배치가 없습니다.'); return; }
		target.innerHTML = '<div class="run-card"><div class="run-line">' + badge('jobStatus', latest.status) + '<b>' +
			esc(label('jobType', latest.jobType)) + '</b><span class="text-muted text-sm">' + esc(fmt.tsKst(latest.startedAt)) +
			' 시작 · ' + esc(fmt.ago(latest.startedAt)) + '</span></div><p class="text-sm" style="margin-top:var(--sp-2)">' +
			esc(latest.errorMessage || latest.summary || '결과 요약 없음') + '</p></div>';
	}

	function renderRecent(rows) {
		const body = $('dashRecent');
		if (!Array.isArray(rows)) {
			body.innerHTML = view.row(5, view.errorHtml(rows.error, '최근 장학금을'));
			bindRetry(body, loadDashboard);
			return;
		}
		body.innerHTML = rows.length ? rows.map(row => '<tr class="clickable' + (row.softDeleted ? ' is-deleted' : '') +
			'" data-open-scholarship="' + row.scholarshipId + '">' + cells([
			'<span class="cell-title">#' + row.scholarshipId + ' ' + esc(row.title || '(제목 없음)') + '</span>' +
				(row.softDeleted ? ' ' + deletedBadge(row) : '') + '<span class="cell-sub">' + esc(row.provider || '기관 없음') + '</span>',
			sourceHtml(row.source),
			badge('recruitment', row.recruitmentStatus),
			missingHtml(row),
			'<span class="nowrap">' + esc(fmt.ts(row.createdAt)) + '</span>'
		]) + '</tr>').join('') : view.row(5, view.emptyHtml('최근 등록된 장학금이 없습니다.'));
	}

	function renderQuality(list) {
		const rate = (part, total) => {
			const value = total ? Math.round(part * 100 / total) : 0;
			return '<span class="rate ' + (value < 30 ? 'low' : value < 60 ? 'mid' : '') + '">' + value + '%</span>';
		};
		const rows = [...list].sort((a, b) => b.total - a.total);
		$('dashQuality').innerHTML = rows.length ? rows.map(s => '<tr>' + cells([sourceHtml(s.source)]) +
			'<td class="num">' + fmt.num(s.total) + '</td><td class="num">' + rate(s.withSummary, s.total) + '</td><td class="num">' +
			rate(s.withAmount, s.total) + '</td><td class="num">' + rate(s.withHomepageUrl, s.total) + '</td><td class="num">' +
			rate(s.withPoster, s.total) + '</td></tr>').join('') : view.row(6, view.emptyHtml('집계할 장학금이 없습니다.'));
	}

	/* ================================================================== 상세 패널 */

	/** 패널 id → 지금 보여 주는 대상. 쓰기 작업 뒤 같은 대상을 다시 불러온다. */
	const panels = {};

	function scholarshipDetailHtml(data) {
		const s = data.scholarship, check = data.statusCheck;
		const conditions = data.conditions || [], documents = data.documents || [], images = data.images || [], raws = data.rawScholarships || [];
		const deleted = Boolean(s.deletedAt);
		const head = '<div class="detail-head"><div class="badges">' + badge('recruitment', s.recruitmentStatus) +
			(deleted ? '<span class="badge b-danger">내려짐</span>' : '') +
			(s.noticeKind && s.noticeKind !== 'RECRUITMENT' ? badge('noticeKind', s.noticeKind, 'b-warning') : '') +
			(s.verified ? '<span class="badge b-success" title="관리자가 확인한 장학금">검증됨</span>' : '') + '</div>' +
			'<h2>#' + s.id + ' ' + esc(s.title || '(제목 없음)') + '</h2><div class="text-muted text-sm">' + esc(s.provider || '기관 없음') +
			' · ' + sourceHtml(s.primarySource) + '</div><div class="actions">' +
			(!deleted ? '<button type="button" class="btn btn-sm btn-primary" data-act="edit">통합 수정</button>' : '') +
			link(s.detailUrl || s.homepageUrl) +
			(!deleted ? '<button type="button" class="btn btn-sm" data-act="report">신고 남기기</button>' : '') +
			(deleted ? '<button type="button" class="btn btn-sm btn-primary" data-act="restore">복원</button>'
				: '<button type="button" class="btn btn-sm btn-danger-ghost" data-act="takedown">내리기</button>') + '</div></div>';
		const deletedNotice = deleted ? '<div class="notice danger banner"><b>목록에서 내린 장학금입니다.</b> 사용자에게 보이지 않습니다. 내린 시각 ' +
			esc(fmt.tsKst(s.deletedAt)) + '</div>' : '';
		const checkNotice = check && !check.consistent && !deleted ? '<div class="notice warn banner"><b>모집 상태와 날짜가 맞지 않습니다.</b><ul>' +
			check.warnings.map(w => '<li>' + esc(w) + '</li>').join('') + '</ul></div>' : '';
		const info = '<div class="detail-section"><h3>기본 정보</h3><dl class="kv">' +
			'<dt>유형</dt><dd>' + esc(label('scholarshipType', s.scholarshipType)) + '</dd>' +
			'<dt>모집 기간</dt><dd>' + esc(fmt.period(s.applicationStartAt, s.applicationEndAt)) + ' <span class="text-muted text-sm">(공고 기준)</span></dd>' +
			'<dt>지원 금액</dt><dd>' + esc(fmt.won(s.amount)) + '</dd>' +
			'<dt>선발 인원</dt><dd>' + esc(s.selectionCount == null ? '-' : s.selectionCount + '명') + '</dd>' +
			'<dt>문의처</dt><dd>' + esc(s.contact || '-') + '</dd>' +
			'<dt>제출</dt><dd>' + esc(label('channel', s.submissionChannel, '미정')) + (s.submissionMethod ? ' · ' + esc(s.submissionMethod) : '') + '</dd>' +
			'<dt>자기소개서</dt><dd>' + esc(label('requirement', s.essayRequirement, '언급 없음')) + '</dd>' +
			'<dt>면접</dt><dd>' + esc(label('requirement', s.interviewRequirement, '언급 없음')) + '</dd>' +
			'<dt>홈페이지</dt><dd>' + (safeUrl(s.homepageUrl) ? link(s.homepageUrl, s.homepageUrl, 'truncate') : '-') + '</dd>' +
			'<dt>등록·수정</dt><dd>' + esc(fmt.ts(s.createdAt)) + ' / ' + esc(fmt.ts(s.updatedAt)) + ' <span class="text-muted text-sm">KST</span></dd>' +
			'</dl></div>';
		const text = '<div class="detail-section"><h3>요약·설명</h3><p>' + esc(s.summary || '요약 없음') + '</p>' +
			(s.description ? '<details><summary>상세 설명 펼치기</summary><div class="raw">' + esc(s.description) + '</div></details>' : '') + '</div>';
		const imageHtml = '<div class="detail-section"><h3>이미지 ' + images.length + '개</h3>' + (images.length ? images.map(image =>
			(safeUrl(image.previewUrl) ? '<img class="thumb" alt="" loading="lazy" src="' + esc(image.previewUrl) + '">' : '') +
			'<div class="text-sm text-muted truncate" title="' + esc(image.sourceUrl || '') + '">' + esc(image.originalName || label('targetType', image.imageType, '이미지')) +
			' · 원본 ' + esc(image.sourceUrl || '-') + '</div>').join('') : '<p class="text-muted text-sm">없음</p>') + '</div>';
		const conditionHtml = '<div class="detail-section"><h3>지원 조건 ' + conditions.length + '개</h3>' + (conditions.length ? conditions.map(c =>
			'<div class="box"><b>' + esc(label('conditionType', c.conditionType)) + '</b> · ' + esc(label('necessity', c.necessity)) + ' · ' +
			esc(label('operator', c.operator)) + (c.autoExtracted ? ' <span class="badge" title="LLM 이 자동으로 뽑은 조건">자동</span>' : '') +
			'<br>' + esc(c.valueString || [c.valueInt, c.valueIntMax].filter(v => v != null).join(' ~ ') || '-') + '</div>').join('')
			: '<p class="text-muted text-sm">없음</p>') + '</div>';
		const documentHtml = '<div class="detail-section"><h3>제출서류 ' + documents.length + '개</h3>' + (documents.length ? '<ul class="summary-list">' +
			documents.map(d => '<li>' + esc(d.name) + (d.essay ? ' <span class="badge b-info">자기소개서</span>' : '') +
				(safeUrl(d.downloadUrl) ? ' ' + link(d.downloadUrl, '양식', 'btn-link') : '') + '</li>').join('') + '</ul>'
			: '<p class="text-muted text-sm">없음</p>') + '</div>';
		const rawHtml = '<div class="detail-section"><h3>연결된 원문 ' + raws.length + '개</h3>' + (raws.length ? raws.map(r =>
			'<details><summary>원문 #' + r.id + ' · ' + esc(r.source) + ' · ' + esc(label('parse', r.parseStatus)) + ' · 수집 ' + esc(fmt.ts(r.crawledAt)) +
			'</summary>' + (r.parseError ? '<div class="notice danger">' + esc(r.parseError) + '</div>' : '') +
			(safeUrl(r.sourceUrl) ? '<p style="margin-bottom:var(--sp-1)">' + link(r.sourceUrl, '원문 사이트') + '</p>' : '') +
			'<div class="raw">' + esc(r.rawHtml || (r.rawJson ? JSON.stringify(r.rawJson, null, 2) : '원문 없음')) + '</div></details>').join('')
			: '<p class="text-muted text-sm">수기 등록이라 원문이 없습니다.</p>') + '</div>';
		return head + deletedNotice + checkNotice + info + text + imageHtml + conditionHtml + documentHtml + rawHtml;
	}

	function bindScholarshipActions(root, data) {
		const id = data.scholarship.id;
		const on = (act, fn) => root.querySelectorAll('[data-act="' + act + '"]').forEach(button => button.onclick = () => fn(button));
		on('edit', () => openEdit({scholarshipId: id}));
		on('report', () => reportScholarship(data.scholarship));
		on('takedown', button => takedownScholarship(data.scholarship, button));
		on('restore', () => restoreScholarship(Object.assign({scholarshipId: id, title: data.scholarship.title,
			deletedAt: data.scholarship.deletedAt}, scholarshipRows.get(id) || {})));
		root.querySelectorAll('[data-open-scholarship]').forEach(el => el.onclick = () => openScholarshipModal(Number(el.dataset.openScholarship)));
	}

	async function openScholarshipPanel(panelId, id) {
		panels[panelId] = {kind: 'scholarship', id: Number(id)};
		const target = $(panelId);
		await view.load(target, async () => {
			const data = await api('/api/v1/scholarships/admin/scholarships/' + id);
			if (data.statusCheck) fmt.calibrate(data.statusCheck.serverNow);
			target.innerHTML = scholarshipDetailHtml(data);
			bindScholarshipActions(target, data);
		}, {what: '장학금 상세를', keep: false});
	}

	function rawHeadHtml(raw) {
		return '<div class="detail-head"><div class="badges">' + badge('parse', raw.parseStatus) + '</div><h2>원문 #' + raw.rawId + '</h2>' +
			'<div class="text-muted text-sm">' + sourceHtml(raw.source) + ' · 게시물 ' + esc(raw.sourceId || '-') + ' · 수집 ' + esc(fmt.tsKst(raw.crawledAt)) + '</div>' +
			'<div class="actions">' + link(raw.sourceUrl, '원문 사이트 열기') +
			(raw.scholarship ? '' : '<button type="button" class="btn btn-sm btn-primary" data-act="refine">수기 정제</button>') + '</div></div>' +
			(raw.parseError ? '<div class="notice danger banner"><b>파싱 오류</b> ' + esc(raw.parseError) + '</div>' : '') +
			'<div class="detail-section"><details' + (raw.scholarship ? '' : ' open') + '><summary>수집 원문 보기</summary><div class="raw">' +
			esc(raw.rawHtml || (raw.rawJson ? JSON.stringify(raw.rawJson, null, 2) : '원문 없음')) + '</div></details></div>';
	}

	async function openRawPanel(panelId, rawId) {
		panels[panelId] = {kind: 'raw', id: Number(rawId)};
		const target = $(panelId);
		await view.load(target, async () => {
			const raw = await api('/api/v1/scholarships/admin/raw/' + rawId);
			target.innerHTML = rawHeadHtml(raw) + (raw.scholarship
				? '<div class="notice success banner">이 원문으로 만들어진 장학금 #' + raw.scholarshipId + '</div>' + scholarshipDetailHtml(raw.scholarship) : '');
			if (raw.scholarship) bindScholarshipActions(target, raw.scholarship);
			const refine = target.querySelector('[data-act="refine"]');
			if (refine) refine.onclick = () => openEdit({rawId: raw.rawId});
		}, {what: '원문 상세를', keep: false});
	}

	/** 목록 화면이 아닌 곳(신고·중복·배치 실패 등)에서 장학금 상세를 모달로 연다. */
	async function openScholarshipModal(id) {
		const body = document.createElement('div');
		body.innerHTML = view.loadingHtml('장학금 상세를 불러오는 중…');
		ui.modal({title: '장학금 #' + id, size: 'lg', body, onOpen: () => {
			view.load(body, async () => {
				const data = await api('/api/v1/scholarships/admin/scholarships/' + id);
				body.innerHTML = scholarshipDetailHtml(data);
				bindScholarshipActions(body, data);
			}, {what: '장학금 상세를', keep: false});
		}});
	}

	/** 쓰기 작업 뒤: 배지·대시보드·열려 있는 상세 패널을 바로 갱신한다. */
	function afterWrite({scholarshipId, reload} = {}) {
		refreshCounts().catch(() => {});
		dashboardStale = true;
		if (currentPage === 'dashboard') loadDashboard();
		Object.entries(panels).forEach(([panelId, panel]) => {
			if (!$(panelId) || !panel) return;
			if (panel.kind === 'scholarship' && (!scholarshipId || panel.id === Number(scholarshipId))) openScholarshipPanel(panelId, panel.id);
			if (panel.kind === 'raw') openRawPanel(panelId, panel.id);
		});
		if (reload) reload();
	}

	/* ================================================================== 수정 모달(통합 수정·수기 정제)·수기 등록 폼 */

	const FIELDS = [
		{name: 'title', label: '장학금명', required: true},
		{name: 'provider', label: '운영 기관'},
		{name: 'scholarshipType', label: '장학금 유형', type: 'select', group: 'scholarshipType'},
		{name: 'recruitmentStatus', label: '모집 상태', type: 'select', group: 'recruitment',
			tip: '사람이 직접 고릅니다. 저장해도 날짜로 다시 계산하지 않습니다.'},
		{name: 'applicationStartAt', label: '모집 시작', type: 'datetime-local', help: '공고에 적힌 날짜(KST)'},
		{name: 'applicationEndAt', label: '모집 마감', type: 'datetime-local', help: '공고에 적힌 날짜(KST)'},
		{name: 'amount', label: '지원 금액(원)', type: 'number'},
		{name: 'selectionCount', label: '선발 인원', type: 'number'},
		{name: 'contact', label: '문의처'},
		{name: 'summary', label: '한 줄 요약', wide: true},
		{name: 'description', label: '상세 설명', type: 'textarea', wide: true},
		{name: 'homepageUrl', label: '홈페이지 URL', type: 'url'},
		{name: 'detailUrl', label: '상세(원문) URL', type: 'url'},
		{name: 'imageSourceUrl', label: '새 포스터 이미지 URL', type: 'url', help: '비우면 이미지를 바꾸지 않습니다', noValue: true},
		{name: 'noticeKind', label: '공지 종류', type: 'select', group: 'noticeKind', blank: '미정', editOnly: true,
			tip: '장학금 아님·일반 안내는 사용자 목록에서 빠집니다. 선발 결과 안내는 모집 기간이 없어도 정상입니다.'},
		{name: 'submissionChannel', label: '제출 경로', type: 'select', group: 'channel', blank: '미정'},
		{name: 'submissionMethod', label: '제출 방법'},
		{name: 'submissionEvidence', label: '제출 근거 문장', tip: '제출 방법을 판단한 공고 문장을 그대로 붙여 넣습니다.'},
		{name: 'essayRequirement', label: '자기소개서', type: 'select', group: 'requirement', blank: '언급 없음'},
		{name: 'essayEvidence', label: '자기소개서 근거 문장'},
		{name: 'interviewRequirement', label: '면접', type: 'select', group: 'requirement', blank: '언급 없음'},
		{name: 'interviewEvidence', label: '면접 근거 문장'},
		{name: 'combined', label: '통합 공고', type: 'checkbox', tip: '한 게시물에 여러 장학금이 함께 실린 공고입니다.'},
		{name: 'rawHtml', label: '원문 HTML 또는 본문', type: 'textarea', wide: true, createOnly: true,
			help: '원문을 보관해야 할 때 붙여 넣습니다.'}
	];

	/** mode: create(수기 등록) · edit(통합 수정) · refine(원문 수기 정제) */
	function fieldsHtml(values, mode) {
		return FIELDS.filter(f => !(f.editOnly && mode === 'create') && !(f.createOnly && mode !== 'create')).map(f => {
			const raw = f.noValue ? '' : values[f.name];
			const value = f.type === 'datetime-local' ? fmt.inputDate(raw) : raw;
			const labelHtml = '<label for="f-' + mode + '-' + f.name + '">' + esc(f.label) + (f.required ? ' <span class="req">*</span>' : '') +
				(f.tip ? tip(f.tip) : '') + '</label>';
			const idAttr = ' id="f-' + mode + '-' + f.name + '" name="' + f.name + '"';
			let control;
			if (f.type === 'select') {
				let blank = f.blank;
				if (f.name === 'recruitmentStatus') blank = mode === 'edit' ? null : '날짜로 자동 계산';
				if (f.name === 'scholarshipType') blank = null;
				control = '<select class="select"' + idAttr + '>' + options(f.group, value || (f.name === 'scholarshipType' && mode !== 'edit' ? 'EXTERNAL' : ''), blank) + '</select>';
			} else if (f.type === 'textarea') {
				control = '<textarea class="textarea"' + idAttr + '>' + esc(value || '') + '</textarea>';
			} else if (f.type === 'checkbox') {
				return '<div class="form-field"><span class="form-label">&nbsp;</span><label class="check"><input type="checkbox"' + idAttr +
					(value ? ' checked' : '') + '> ' + esc(f.label) + (f.tip ? tip(f.tip) : '') + '</label></div>';
			} else {
				control = '<input class="input" type="' + (f.type || 'text') + '"' + idAttr + (f.type === 'number' ? ' min="0"' : '') +
					' value="' + esc(value == null ? '' : value) + '">';
			}
			const extra = f.name === 'recruitmentStatus' ? '<span class="field-warn" data-status-warn></span>' : '';
			return '<div class="form-field' + (f.wide || f.type === 'textarea' ? ' wide' : '') + '">' + labelHtml + control +
				(f.help ? '<span class="field-help">' + esc(f.help) + '</span>' : '') + extra + '</div>';
		}).join('');
	}

	function conditionRow(value = {}) {
		const row = document.createElement('div');
		row.className = 'edit-row';
		row.dataset.kind = 'condition';
		const refs = value.refs || [];
		row.innerHTML = '<select class="select" data-field="conditionType" aria-label="조건 유형">' + options('conditionType', value.conditionType, '조건 유형 선택') + '</select>' +
			'<select class="select" data-field="necessity" aria-label="필수/우대">' + options('necessity', value.necessity || 'REQUIRED') + '</select>' +
			'<select class="select" data-field="operator" aria-label="비교 방식">' + options('operator', value.operator || 'EQ') + '</select>' +
			'<input class="input grow" data-field="valueString" placeholder="조건 원문 * (공고 문장)" value="' + esc(value.valueString || '') + '">' +
			'<input class="input" style="width:90px" data-field="valueInt" type="number" placeholder="숫자" value="' + esc(value.valueInt == null ? '' : value.valueInt) + '">' +
			'<input class="input" style="width:90px" data-field="valueIntMax" type="number" placeholder="최대" value="' + esc(value.valueIntMax == null ? '' : value.valueIntMax) + '">' +
			'<input class="input" style="width:150px" data-field="refLabels" placeholder="새 참조 라벨(쉼표)" title="학교·학과 같은 참조 데이터 이름을 쉼표로 적으면 서버가 찾아 연결합니다">' +
			'<input type="hidden" data-field="refIds" value="' + esc(refs.filter(r => r.refId != null).map(r => r.refId).join(',')) + '">' +
			'<input type="hidden" data-field="refCodes" value="' + esc(refs.filter(r => r.refCode).map(r => r.refCode).join(',')) + '">' +
			(refs.length ? '<span class="badge" title="기존 참조 ' + esc(refs.map(r => r.refCode || r.refId).join(', ')) + '">참조 ' + refs.length + '</span>' : '') +
			'<button type="button" class="btn btn-sm btn-danger-ghost" data-remove>삭제</button>';
		row.querySelector('[data-remove]').onclick = () => row.remove();
		return row;
	}

	function documentRow(value = {}) {
		const row = document.createElement('div');
		row.className = 'edit-row';
		row.dataset.kind = 'document';
		row.innerHTML = '<input class="input grow" data-field="name" placeholder="서류명 *" value="' + esc(value.name || '') + '">' +
			'<label class="check"><input type="checkbox" data-field="essay"' + (value.essay ? ' checked' : '') + '> 자기소개서</label>' +
			'<input class="input grow" data-field="downloadUrl" type="url" placeholder="양식 다운로드 URL" value="' + esc(value.downloadUrl || '') + '">' +
			'<button type="button" class="btn btn-sm btn-danger-ghost" data-remove>삭제</button>';
		row.querySelector('[data-remove]').onclick = () => row.remove();
		return row;
	}

	/** 폼 값을 서버 요청 형태로 모은다. 비어 있는 필수값은 빨갛게 표시하고 오류를 던진다. */
	function collectForm(root, mode, extra = {}, {validate = true} = {}) {
		const field = name => root.querySelector('[name="' + name + '"]');
		const value = name => field(name) ? field(name).value : '';
		const number = name => value(name) === '' ? null : Number(value(name));
		const list = text => text.split(',').map(v => v.trim()).filter(Boolean);
		const problems = [];
		root.querySelectorAll('.is-invalid').forEach(el => el.classList.remove('is-invalid'));
		const mark = (el, message) => { el.classList.add('is-invalid'); problems.push(message); };
		if (!value('title').trim()) mark(field('title'), '장학금명을 입력하세요.');
		const start = value('applicationStartAt'), end = value('applicationEndAt');
		if (start && end && end < start) mark(field('applicationEndAt'), '모집 마감이 모집 시작보다 빠릅니다.');
		const conditions = [...root.querySelectorAll('[data-kind="condition"]')].map((row, index) => {
			const get = name => row.querySelector('[data-field="' + name + '"]');
			if (!get('conditionType').value) mark(get('conditionType'), (index + 1) + '번째 조건의 유형을 고르세요.');
			if (!get('valueString').value.trim()) mark(get('valueString'), (index + 1) + '번째 조건의 원문을 입력하세요.');
			return {
				conditionType: get('conditionType').value, necessity: get('necessity').value, operator: get('operator').value,
				valueString: get('valueString').value.trim(),
				valueInt: get('valueInt').value === '' ? null : Number(get('valueInt').value),
				valueIntMax: get('valueIntMax').value === '' ? null : Number(get('valueIntMax').value),
				refLabels: list(get('refLabels').value), refIds: list(get('refIds').value).map(Number), refCodes: list(get('refCodes').value)
			};
		});
		const documents = [...root.querySelectorAll('[data-kind="document"]')].map((row, index) => {
			const get = name => row.querySelector('[data-field="' + name + '"]');
			if (!get('name').value.trim()) mark(get('name'), (index + 1) + '번째 서류의 이름을 입력하세요.');
			return {name: get('name').value.trim(), essay: get('essay').checked, displayOrder: index, downloadUrl: textOrNull(get('downloadUrl').value)};
		});
		if (!validate) root.querySelectorAll('.is-invalid').forEach(el => el.classList.remove('is-invalid'));
		if (validate && problems.length) {
			const first = root.querySelector('.is-invalid');
			if (first) first.focus();
			throw new Error(problems.join(' '));
		}
		const payload = {
			title: value('title').trim(), provider: textOrNull(value('provider')), summary: textOrNull(value('summary')),
			description: textOrNull(value('description')), scholarshipType: value('scholarshipType'),
			applicationStartAt: textOrNull(start), applicationEndAt: textOrNull(end),
			recruitmentStatus: textOrNull(value('recruitmentStatus')), selectionCount: number('selectionCount'), amount: number('amount'),
			homepageUrl: textOrNull(value('homepageUrl')), detailUrl: textOrNull(value('detailUrl')),
			combined: field('combined') ? field('combined').checked : false,
			submissionMethod: textOrNull(value('submissionMethod')), submissionChannel: textOrNull(value('submissionChannel')),
			submissionEvidence: textOrNull(value('submissionEvidence')), contact: textOrNull(value('contact')),
			essayRequirement: textOrNull(value('essayRequirement')), essayEvidence: textOrNull(value('essayEvidence')),
			interviewRequirement: textOrNull(value('interviewRequirement')), interviewEvidence: textOrNull(value('interviewEvidence')),
			conditions, documents, imageSourceUrl: textOrNull(value('imageSourceUrl'))
		};
		if (mode !== 'create') payload.noticeKind = textOrNull(value('noticeKind'));
		return Object.assign(payload, extra);
	}

	function editBody(values, mode, rawText, detail) {
		const body = document.createElement('form');
		body.noValidate = true;
		body.innerHTML = (rawText != null ? '<div class="notice neutral">원문은 읽기 전용입니다. 원문을 보며 아래 칸을 채우세요.</div>' +
			'<details style="margin-bottom:var(--sp-3)"><summary>수집 원문 보기</summary><div class="raw">' + esc(rawText || '원문 없음') + '</div></details>' : '') +
			'<div class="form-grid">' + fieldsHtml(values, mode) + '</div>' +
			'<div class="sub-form"><div class="sub-form-head"><h3>지원 조건</h3><button type="button" class="btn btn-sm" data-add="condition">조건 추가</button></div><div class="edit-list" data-list="condition"></div></div>' +
			'<div class="sub-form"><div class="sub-form-head"><h3>제출서류</h3><button type="button" class="btn btn-sm" data-add="document">서류 추가</button></div><div class="edit-list" data-list="document"></div></div>';
		const conditionList = body.querySelector('[data-list="condition"]'), documentList = body.querySelector('[data-list="document"]');
		(detail ? detail.conditions || [] : []).forEach(c => conditionList.appendChild(conditionRow(c)));
		(detail ? detail.documents || [] : []).forEach(d => documentList.appendChild(documentRow(d)));
		body.querySelector('[data-add="condition"]').onclick = () => conditionList.appendChild(conditionRow());
		body.querySelector('[data-add="document"]').onclick = () => documentList.appendChild(documentRow());
		body.addEventListener('submit', event => event.preventDefault());
		return body;
	}

	/**
	 * 모집 상태와 날짜가 서로 맞는지(서버 RecruitmentStatusCheck 와 같은 규칙). 상태를 고쳐 주지 않고 경고만 한다.
	 * 공고 날짜는 한국 날짜라 KST 현재 시각과 비교한다.
	 */
	function statusWarnings(status, start, end) {
		const now = fmt.nowKstWall();
		const passed = Boolean(end) && end < now, beforeStart = Boolean(start) && start > now;
		const warnings = [];
		if (start && end && end < start) warnings.push('마감일이 시작일보다 빠릅니다.');
		if (status === 'OPEN') {
			if (passed) warnings.push('마감일이 지났는데 모집 중입니다. 사용자에게 마감 공고가 모집 중으로 보입니다.');
			if (beforeStart) warnings.push('시작일 전인데 모집 중입니다. 모집 예정이 맞는지 확인하세요.');
		} else if (status === 'ALWAYS_OPEN') {
			if (passed) warnings.push('마감일이 지났는데 상시모집입니다.');
			else if (end) warnings.push('마감일이 있는데 상시모집입니다. 모집 중이 맞는지 확인하세요.');
		} else if (status === 'UPCOMING') {
			if (passed) warnings.push('마감일이 지났는데 모집 예정입니다.');
			else if (start && !beforeStart) warnings.push('시작일이 지났는데 모집 예정입니다.');
		} else if (status === 'CLOSED') {
			if (end && !passed) warnings.push('마감일 전인데 마감입니다. 조기 마감이 아니라면 확인하세요.');
		}
		return warnings;
	}

	/** 폼의 모집 상태·날짜가 바뀔 때마다 모순 경고를 상태 칸 아래에 보여 준다(저장은 막지 않음). */
	function bindStatusWarning(root) {
		const box = root.querySelector('[data-status-warn]');
		if (!box) return () => [];
		const read = () => {
			const get = name => (root.querySelector('[name="' + name + '"]') || {}).value || '';
			const warnings = get('recruitmentStatus') ? statusWarnings(get('recruitmentStatus'), get('applicationStartAt'), get('applicationEndAt')) : [];
			box.textContent = warnings.length ? '⚠ ' + warnings.join(' ') + ' (저장은 할 수 있습니다)' : '';
			return warnings;
		};
		['recruitmentStatus', 'applicationStartAt', 'applicationEndAt'].forEach(name => {
			const el = root.querySelector('[name="' + name + '"]');
			if (el) { el.addEventListener('change', read); el.addEventListener('input', read); }
		});
		read();
		return read;
	}

	const FIELD_BY_NAME = Object.fromEntries(FIELDS.map(f => [f.name, f]));
	function displayValue(name, value) {
		const f = FIELD_BY_NAME[name] || {};
		if (value == null || value === '') return '(비어 있음)';
		if (f.type === 'select') return label(f.group, value);
		if (f.type === 'datetime-local') return fmt.biz(value);
		if (f.type === 'checkbox') return value ? '예' : '아니오';
		if (f.type === 'number') return fmt.num(value);
		return String(value);
	}
	const conditionText = c => label('conditionType', c.conditionType, '유형 없음') + ' · ' + label('necessity', c.necessity) + ' · ' +
		label('operator', c.operator) + ' · ' + (c.valueString || '') + ([c.valueInt, c.valueIntMax].some(v => v != null) ? ' (' + [c.valueInt, c.valueIntMax].filter(v => v != null).join('~') + ')' : '');
	const documentText = d => d.name + (d.essay ? ' (자기소개서)' : '') + (d.downloadUrl ? ' · 양식 링크' : '');

	/** 저장 전 비교: 바뀐 필드만 [이름, 이전, 이후] 로. 조건·서류는 추가·삭제된 항목으로. */
	function diffPayload(before, after) {
		const rows = [];
		Object.keys(after).forEach(key => {
			if (['conditions', 'documents', 'source', 'imageSourceUrl'].includes(key)) return;
			const a = before[key] == null || before[key] === '' ? null : before[key], b = after[key] == null || after[key] === '' ? null : after[key];
			if (JSON.stringify(a) !== JSON.stringify(b)) rows.push([FIELD_BY_NAME[key] ? FIELD_BY_NAME[key].label : key, displayValue(key, a), displayValue(key, b), key]);
		});
		if (after.imageSourceUrl) rows.push(['포스터 이미지', '(현재 이미지)', '새 이미지: ' + after.imageSourceUrl, 'imageSourceUrl']);
		const listDiff = (name, oldList, newList, text) => {
			const oldTexts = oldList.map(text), newTexts = newList.map(text);
			if (JSON.stringify(oldTexts) === JSON.stringify(newTexts)) return;
			const removed = oldTexts.filter(t => !newTexts.includes(t)), added = newTexts.filter(t => !oldTexts.includes(t));
			rows.push([name + ' (' + oldList.length + '개 → ' + newList.length + '개)',
				removed.length ? removed.map(t => '− ' + t).join('\n') : (added.length ? '' : '순서만 바뀜'),
				added.length ? added.map(t => '+ ' + t).join('\n') : (removed.length ? '' : '순서만 바뀜'), name]);
		};
		listDiff('지원 조건', before.conditions || [], after.conditions || [], conditionText);
		listDiff('제출서류', before.documents || [], after.documents || [], documentText);
		return rows;
	}

	/** 통합 수정({scholarshipId}) 또는 원문 수기 정제({rawId}). */
	async function openEdit({scholarshipId, rawId}) {
		let detail = null, raw = null;
		try {
			if (scholarshipId) detail = await api('/api/v1/scholarships/admin/scholarships/' + scholarshipId);
			else raw = await api('/api/v1/scholarships/admin/raw/' + rawId);
		} catch (error) {
			ui.toast(WC.errorText(error), 'error');
			return;
		}
		if (raw && raw.scholarship) { detail = raw.scholarship; scholarshipId = raw.scholarshipId; }
		const mode = scholarshipId ? 'edit' : 'refine';
		const s = detail ? detail.scholarship : {title: '', scholarshipType: 'EXTERNAL', homepageUrl: raw.sourceUrl, detailUrl: raw.sourceUrl};
		const rawText = raw ? (raw.rawHtml || (raw.rawJson ? JSON.stringify(raw.rawJson, null, 2) : '')) : null;
		const body = editBody(s, mode, mode === 'refine' ? rawText : null, detail);
		const sourceExtra = raw && mode === 'refine' ? {source: {sourceUrl: raw.sourceUrl, rawHtml: raw.rawHtml}} : {source: null};
		const baseline = collectForm(body, mode, sourceExtra, {validate: false});
		const readWarnings = bindStatusWarning(body);
		await ui.modal({
			title: mode === 'edit' ? '#' + scholarshipId + ' 통합 수정' : '원문 #' + raw.rawId + ' 수기 정제',
			subtitle: mode === 'edit' ? '장학금 본문·조건·서류·자소서/면접 분기를 함께 저장합니다.' : '이 원문으로 새 장학금을 만듭니다.',
			size: 'xl', body, confirmLabel: mode === 'edit' ? '저장' : '장학금 만들기',
			footNote: mode === 'edit' ? '저장 기록은 [감사·복구]에 남아 되돌릴 수 있습니다.' : '',
			onConfirm: async () => {
				const payload = collectForm(body, mode, sourceExtra);
				const path = mode === 'edit' ? '/api/v1/scholarships/manual/' + scholarshipId + '/full' : '/api/v1/scholarships/admin/raw/' + raw.rawId + '/manual';
				const save = () => api(path, {method: mode === 'edit' ? 'PUT' : 'POST', body: payload});
				let result;
				if (mode === 'edit') {
					const rows = diffPayload(baseline, payload);
					if (!rows.length) { ui.toast('바뀐 내용이 없습니다.', 'info'); return false; }
					const warnings = readWarnings();
					result = await ui.confirmAction({
						title: '변경 내용 확인', subtitle: '#' + scholarshipId + ' ' + (s.title || ''), kind: 'primary', size: 'lg',
						confirmLabel: rows.length + '개 항목 저장',
						extraHtml: (warnings.length ? '<div class="notice warn"><b>모집 상태와 날짜가 맞지 않습니다.</b> 이대로 저장할 수 있지만 사용자 화면에 그대로 보입니다.<ul>' +
							warnings.map(w => '<li>' + esc(w) + '</li>').join('') + '</ul></div>' : '') +
							'<table class="diff-table"><thead><tr><th>항목</th><th>이전</th><th>이후</th></tr></thead><tbody>' + rows.map(r =>
								'<tr' + (r[3] === 'recruitmentStatus' ? ' class="warn-row"' : '') + '><td class="nowrap"><b>' + esc(r[0]) + '</b></td><td class="val before">' +
								esc(r[1]) + '</td><td class="val after">' + esc(r[2]) + '</td></tr>').join('') + '</tbody></table>' +
							'<p class="field-help" style="margin:var(--sp-2) 0 var(--sp-3)">모집 상태는 날짜로 자동 계산되지 않습니다. 고른 값 그대로 저장됩니다.</p>',
						reversible: 'yes', reversibleText: '[감사·복구]에서 이 저장 기록을 열어 필드별로 되돌릴 수 있습니다.',
						onConfirm: () => save()
					});
					if (!result) return false;
				} else {
					result = await save();
				}
				ui.toast(mode === 'edit' ? '#' + scholarshipId + ' 장학금을 저장했습니다.' : '원문 #' + raw.rawId + '을(를) 장학금으로 만들었습니다.');
				if (result && result.imageError) ui.toast('장학금은 저장했지만 이미지는 저장하지 못했습니다: ' + result.imageError, 'warn');
				if (result && result.statusCheck && !result.statusCheck.consistent) {
					ui.toast('저장했지만 모집 상태와 날짜가 맞지 않습니다: ' + result.statusCheck.warnings.join(' '), 'warn');
				}
				afterWrite({scholarshipId, reload: reloadCurrentList});
				return result;
			}
		});
	}

	/* ================================================================== 화면별 로더 */

	async function loadIntake() {
		const body = $('intakeRows');
		await view.load(body, async () => {
			const page = await api(qs('/api/v1/scholarships/admin/intake', {
				date: $('intakeDate').value, keyword: val('intakeKeyword'), source: val('intakeSource'), status: $('intakeStatus').value,
				page: pageNo.intake, size: PAGE_SIZE, sort: 'crawledAt,desc'
			}));
			renderPager('intakePager', page, 'intake', loadIntake);
			const selected = panels.intakeDetail && panels.intakeDetail.id;
			return page.content.length ? page.content.map(row => '<tr class="clickable' + (row.rawId === selected ? ' selected' : '') +
				'" data-raw="' + row.rawId + '">' + cells([
				'<span class="nowrap">#' + row.rawId + '</span>',
				'<span class="cell-title">' + rawTitle(row) + '</span>' + (row.scholarshipId ? '<span class="cell-sub">장학금 #' + row.scholarshipId + '</span>' : ''),
				sourceHtml(row.source),
				badge('parse', row.parseStatus),
				'<span class="nowrap">' + esc(fmt.ts(row.crawledAt)) + '</span>'
			]) + '</tr>').join('') : view.row(5, view.emptyHtml('이 조건의 수집 원문이 없습니다.', '날짜를 바꾸거나 검색어를 지워 보세요. 수집 배치는 매일 11:00(KST)에 돕니다.'));
		}, {colspan: 5, what: '수집 원문을'});
		bindRowSelect(body, 'raw', id => openRawPanel('intakeDetail', id));
	}

	function rawTitle(row) {
		const title = (row.title || '').trim();
		if (!title || title === row.sourceId || /^\d{8,}$/.test(title)) {
			return '<span class="text-warning">제목 추출 실패</span>' + tip('수집기가 제목을 찾지 못해 게시물 번호만 남았습니다. 원문을 열어 확인하세요.') +
				'<span class="cell-sub">게시물 ' + esc(row.sourceId || title || '-') + '</span>';
		}
		return esc(title);
	}

	/** 목록 행을 누르면 상세를 열고 선택 표시를 옮긴다. */
	function bindRowSelect(body, attr, open) {
		body.querySelectorAll('[data-' + attr + ']').forEach(row => row.onclick = event => {
			if (event.target.closest('button, a, input, label')) return;
			body.querySelectorAll('tr.selected').forEach(tr => tr.classList.remove('selected'));
			row.classList.add('selected');
			open(row.dataset[attr]);
		});
	}

	const RAW_ID_PATTERN = /^(?:raw\s*)?#?\s*(\d{1,10})$/i;
	async function loadFailures() {
		const body = $('failureRows');
		const keyword = val('failureKeyword');
		const rawIdMatch = keyword.match(RAW_ID_PATTERN);
		await view.load(body, async () => {
			// 원문 ID 로 찾을 수 있게 한다. 목록 API 는 게시물 번호·오류 문구만 검색하므로 숫자면 원문을 직접 조회해 맨 위에 붙인다.
			const [page, exact] = await Promise.all([
				api(qs('/api/v1/scholarships/admin/failures', {
					keyword: rawIdMatch ? rawIdMatch[1] : keyword, source: val('failureSource'), status: $('failureStatus').value,
					retryableOnly: $('failureRetryable').checked, page: pageNo.failures, size: PAGE_SIZE, sort: 'updatedAt,desc'
				})),
				rawIdMatch ? api('/api/v1/scholarships/admin/raw/' + rawIdMatch[1]).catch(() => null) : Promise.resolve(null)
			]);
			renderPager('failurePager', page, 'failures', loadFailures);
			const rows = page.content.filter(row => !exact || row.rawId !== exact.rawId);
			const exactRow = exact ? '<tr class="selected">' + failureCells({rawId: exact.rawId, scholarshipId: exact.scholarshipId, source: exact.source,
				status: exact.parseStatus, error: exact.parseError, updatedAt: exact.crawledAt}, '<span class="badge b-brand">원문 ID 일치</span>') + '</tr>' : '';
			const html = exactRow + rows.map(row => '<tr>' + failureCells(row) + '</tr>').join('');
			return html || view.row(7, view.emptyHtml(keyword ? '검색 결과가 없습니다.' : '재처리할 원문이 없습니다.',
				keyword ? '원문 ID 는 숫자만, 게시물 번호나 오류 문구 일부로도 찾을 수 있습니다.' : '모든 원문이 정상 처리됐습니다.'));
		}, {colspan: 7, what: '재처리 대상을'});
		$('failureCheckAll').checked = false;
		updateRetryButton();
		body.querySelectorAll('[data-failure-edit]').forEach(button => button.onclick = () => openEdit({rawId: Number(button.dataset.failureEdit)}));
		body.querySelectorAll('[data-failure-view]').forEach(button => button.onclick = () => openRawModal(Number(button.dataset.failureView)));
		body.querySelectorAll('[data-retry-id]').forEach(box => box.onchange = updateRetryButton);
	}

	function failureCells(row, extraBadge = '') {
		const retryable = String(row.source || '').startsWith('UNIV_');
		const failed = ['FAILED', 'SKIPPED', 'IMAGE_ONLY'].includes(row.status);
		return '<td class="check-col"><input type="checkbox" data-retry-id="' + row.rawId + '"' + (retryable && failed ? '' : ' disabled') +
			' aria-label="원문 #' + row.rawId + ' 선택" title="' + (retryable ? 'LLM 재처리 대상으로 선택' : '대학 공지만 LLM 재처리할 수 있습니다') + '"></td>' + cells([
			badge('parse', row.status) + (extraBadge ? '<br>' + extraBadge : ''),
			'<span class="nowrap cell-title">원문 #' + row.rawId + '</span>' + (row.scholarshipId ? '<span class="cell-sub">장학금 #' + row.scholarshipId + '</span>' : ''),
			sourceHtml(row.source),
			'<span class="clamp-2" title="' + esc(row.error || '') + '">' + esc(row.error || '-') + '</span>',
			'<span class="nowrap">' + esc(fmt.ts(row.updatedAt)) + '</span>',
			'<div class="actions"><button type="button" class="btn btn-sm" data-failure-view="' + row.rawId + '">원문</button>' +
				'<button type="button" class="btn btn-sm" data-failure-edit="' + row.rawId + '">수기 정제</button></div>'
		]);
	}

	function selectedRetryIds() { return [...document.querySelectorAll('#failureRows [data-retry-id]:checked')].map(box => Number(box.dataset.retryId)); }
	function updateRetryButton() {
		const count = selectedRetryIds().length;
		$('retryFailures').disabled = count === 0;
		$('retryFailures').textContent = count ? '선택 ' + count + '건 LLM 재처리' : '선택 LLM 재처리';
	}

	async function openRawModal(rawId) {
		const body = document.createElement('div');
		ui.modal({title: '원문 #' + rawId, size: 'lg', body, onOpen: () => view.load(body, async () => {
			const raw = await api('/api/v1/scholarships/admin/raw/' + rawId);
			body.innerHTML = rawHeadHtml(raw);
			const refine = body.querySelector('[data-act="refine"]');
			if (refine) refine.onclick = () => openEdit({rawId});
		}, {what: '원문을', keep: false})});
	}

	async function loadAnomalies() {
		const body = $('anomalyRows');
		await view.load(body, async () => {
			const page = await api(qs('/api/v1/scholarships/admin/anomalies', {
				keyword: val('anomalyKeyword'), source: val('anomalySource'), status: $('anomalyStatus').value,
				anomalyType: $('anomalyType').value, page: pageNo.anomaly, size: PAGE_SIZE, sort: 'createdAt,desc'
			}));
			renderPager('anomalyPager', page, 'anomaly', loadAnomalies);
			return page.content.length ? page.content.map(row => '<tr>' + cells([
				'#' + row.scholarshipId,
				'<span class="cell-title">' + esc(row.title || '(제목 없음)') + '</span><span class="cell-sub">' + esc(row.provider || '기관 없음') + ' · ' + esc(row.source || 'MANUAL') + '</span>',
				badge('recruitment', row.recruitmentStatus),
				'<span class="nowrap">' + esc(fmt.period(row.applicationStartAt, row.applicationEndAt)) + '</span>',
				'<div class="badges">' + row.anomalyTypes.map(type => '<span class="badge b-danger" title="' + esc(type) + '">' + esc(label('anomaly', type)) + '</span>').join('') + '</div>',
				'<button type="button" class="btn btn-sm" data-anomaly-open="' + row.scholarshipId + '">상세·수정</button>'
			]) + '</tr>').join('') : view.row(6, view.emptyHtml('탐지된 이상이 없습니다.', '필터를 바꿔 보세요.'));
		}, {colspan: 6, what: '이상 데이터를'});
		body.querySelectorAll('[data-anomaly-open]').forEach(button => button.onclick = () => openInAllPage(Number(button.dataset.anomalyOpen)));
	}

	/** 다른 화면에서 전체 장학금 화면으로 넘어가 그 장학금을 연다. */
	function openInAllPage(id) {
		$('scholarshipKeyword').value = '';
		pageNo.scholarship = 0;
		showPage('all', {load: false});
		loadScholarships();
		openScholarshipPanel('scholarshipDetail', id);
	}

	async function loadScholarships() {
		const body = $('scholarshipRows');
		await view.load(body, async () => {
			const page = await api(qs('/api/v1/scholarships/admin/scholarships', {
				page: pageNo.scholarship, size: PAGE_SIZE, sort: 'createdAt,desc', keyword: val('scholarshipKeyword'),
				source: val('scholarshipSource'), status: $('scholarshipStatus').value, includeDeleted: $('includeDeleted').checked
			}));
			renderPager('scholarshipPager', page, 'scholarship', loadScholarships);
			page.content.forEach(row => scholarshipRows.set(row.scholarshipId, row));
			const selected = panels.scholarshipDetail && panels.scholarshipDetail.id;
			return page.content.length ? page.content.map(row => '<tr class="clickable' + (row.softDeleted ? ' is-deleted' : '') +
				(row.scholarshipId === selected ? ' selected' : '') + '" data-scholarship="' + row.scholarshipId + '">' + cells([
				'#' + row.scholarshipId,
				'<span class="cell-title">' + esc(row.title || '(제목 없음)') + '</span><span class="cell-sub">' + esc(row.provider || '기관 없음') + '</span>' +
					(row.softDeleted ? deletedRowInfo(row) : ''),
				row.softDeleted ? deletedBadge(row) + '<div style="margin-top:4px">' + (row.deleteKind === 'MERGE'
					? '<span class="text-muted text-sm" title="병합으로 내린 장학금은 사용자 데이터가 이미 옮겨져 복원할 수 없습니다">복원 불가</span>'
					: '<button type="button" class="btn btn-sm" data-restore="' + row.scholarshipId + '">복원</button>') + '</div>'
					: badge('recruitment', row.recruitmentStatus),
				sourceHtml(row.source),
				missingHtml(row)
			]) + '</tr>').join('') : view.row(5, view.emptyHtml('검색 결과가 없습니다.', '검색어·모집 상태·출처 조건을 바꿔 보세요.'));
		}, {colspan: 5, what: '장학금 목록을'});
		bindRowSelect(body, 'scholarship', id => openScholarshipPanel('scholarshipDetail', id));
		body.querySelectorAll('[data-restore]').forEach(button => button.onclick = event => {
			event.stopPropagation();
			restoreScholarship(scholarshipRows.get(Number(button.dataset.restore)));
		});
	}

	/** 목록에서 본 행(내린 사람·사유·종류). 상세 패널의 복원 모달이 함께 보여 준다. */
	const scholarshipRows = new Map();

	function deletedRowInfo(row) {
		return '<span class="cell-sub text-danger">' + esc(label('deleteKind', row.deleteKind, '내려짐')) + ' · ' + esc(fmt.ts(row.deletedAt)) + ' KST' +
			(row.deletedByName ? ' · ' + esc(row.deletedByName) : '') + '</span>' +
			(row.deleteReason ? '<span class="cell-sub">사유: ' + esc(row.deleteReason) + '</span>' : '');
	}

	async function loadAlways() {
		const body = $('alwaysRows');
		await view.load(body, async () => {
			const page = await api(qs('/api/v1/scholarships/admin/always-open', {page: pageNo.always, size: PAGE_SIZE, sort: 'createdAt,asc'}));
			renderPager('alwaysPager', page, 'always', loadAlways);
			return page.content.length ? page.content.map(row => {
				const days = fmt.ageDays(row.reviewedAt);
				return '<tr>' + cells([
					scholarshipLink(row.id, '#' + row.id + ' ' + (row.title || '')),
					esc(row.provider || '-'),
					'<span class="nowrap">' + esc(fmt.ts(row.createdAt).slice(0, 10)) + '</span>',
					row.reviewedAt ? '<span class="nowrap' + (days > 30 ? ' text-warning' : '') + '">' + esc(fmt.ts(row.reviewedAt)) + '<span class="cell-sub">' + esc(fmt.ago(row.reviewedAt)) + '</span></span>'
						: '<span class="text-warning">확인 기록 없음</span>'
				]) + '<td class="num">' + fmt.num(row.conditionCount) + '</td><td><div class="actions">' + link(row.sourceUrl) +
					'<button type="button" class="btn btn-sm btn-primary" data-always-confirm="' + row.id + '" data-title="' + esc(row.title || '') + '">계속 모집</button>' +
					'<button type="button" class="btn btn-sm btn-danger-ghost" data-always-close="' + row.id + '" data-title="' + esc(row.title || '') + '">마감 처리</button>' +
					'<button type="button" class="btn btn-sm" data-always-edit="' + row.id + '">수정</button></div></td></tr>';
			}).join('') : view.row(6, view.emptyHtml('상시모집 장학금이 없습니다.'));
		}, {colspan: 6, what: '상시모집 목록을'});
		body.querySelectorAll('[data-always-confirm]').forEach(button => button.onclick = () => confirmAlwaysOpen(button));
		body.querySelectorAll('[data-always-close]').forEach(button => button.onclick = () => closeAlwaysOpen(Number(button.dataset.alwaysClose), button.dataset.title));
		body.querySelectorAll('[data-always-edit]').forEach(button => button.onclick = () => openEdit({scholarshipId: Number(button.dataset.alwaysEdit)}));
		bindOpenScholarship(body);
	}

	function bindOpenScholarship(root) {
		root.querySelectorAll('[data-open-scholarship]').forEach(el => el.onclick = event => {
			event.stopPropagation();
			openScholarshipModal(Number(el.dataset.openScholarship));
		});
	}

	/* ---------- 중복 판정 */

	function sideHtml(side, role) {
		return '<article class="candidate"><span class="badge ' + (role === 'keep' ? 'b-success' : 'b-danger') + '">' +
			(role === 'keep' ? '유지할 쪽' : '내릴 쪽') + '</span><h3>' + esc(side.title || '(제목 없음)') + '</h3><dl class="kv">' +
			'<dt>ID</dt><dd>#' + side.scholarshipId + ' ' + (side.verified ? '<span class="badge b-success">검증됨</span>' : '') + '</dd>' +
			'<dt>기관</dt><dd>' + esc(side.provider || '-') + '</dd>' +
			'<dt>유형</dt><dd>' + esc(label('scholarshipType', side.scholarshipType)) + '</dd>' +
			'<dt>모집 기간</dt><dd>' + esc(side.applicationPeriod || '기간 없음') + '</dd>' +
			'<dt>금액</dt><dd>' + esc(fmt.won(side.amount)) + '</dd>' +
			'<dt>선발 인원</dt><dd>' + esc(side.selectionCount == null ? '-' : side.selectionCount + '명') + '</dd>' +
			'<dt>출처</dt><dd>' + sourceHtml(side.source) + '</dd>' +
			'<dt>홈페이지</dt><dd>' + (safeUrl(side.homepageUrl) ? link(side.homepageUrl, side.homepageUrl, 'truncate') : '-') + '</dd></dl>' +
			'<div class="actions" style="margin-top:var(--sp-2)"><button type="button" class="btn btn-sm" data-open-scholarship="' + side.scholarshipId +
			'">원문·상세 보기</button></div></article>';
	}

	function candidateHtml(item) {
		const pending = item.status === 'PENDING';
		return '<div class="card" data-candidate="' + item.candidateId + '"><div class="card-head"><div class="badges">' +
			'<span class="badge">후보 #' + item.candidateId + '</span>' + badge('mergeOrigin', item.origin) + badge('mergeStatus', item.status) + '</div>' +
			'<span class="meta">' + esc(item.reason || '') + '</span></div><div class="compare">' + sideHtml(item.primary, 'keep') +
			'<div class="compare-mid"><span class="arrow">←</span>병합하면<br>오른쪽 데이터가<br>왼쪽으로 옮겨집니다</div>' + sideHtml(item.duplicate, 'drop') + '</div>' +
			'<div class="card-head" style="border-top:1px solid var(--c-border);border-bottom:0">' +
			(pending ? '<span class="meta">두 카드를 비교한 뒤 결정하세요.</span><div class="actions">' +
				'<button type="button" class="btn" data-merge-reject="' + item.candidateId + '">중복 아님</button>' +
				'<button type="button" class="btn btn-primary" data-merge-approve="' + item.candidateId + '">확인 후 병합</button></div>'
				: '<span class="meta">' + esc(label('mergeStatus', item.status)) + ' · ' + esc(fmt.tsKst(item.reviewedAt)) +
				(item.note ? ' · 메모: ' + esc(item.note) : '') + '</span><div class="actions" data-closed-actions></div>') + '</div></div>';
	}

	let duplicateItems = new Map();
	async function loadDuplicates() {
		const target = $('duplicateList');
		await view.load(target, async () => {
			const result = await api(qs('/api/v1/scholarships/merge/candidates', {status: $('duplicateStatus').value || 'PENDING',
				origin: $('duplicateOrigin').value, keyword: val('duplicateKeyword'), page: pageNo.duplicate, size: 10}));
			duplicateItems = new Map(result.items.map(item => [item.candidateId, item]));
			renderPager('duplicatePager', {number: pageNo.duplicate, totalElements: result.totalCount, totalPages: Math.ceil(result.totalCount / 10)},
				'duplicate', loadDuplicates);
			return result.items.length ? result.items.map(candidateHtml).join('')
				: '<div class="card">' + view.emptyHtml('이 조건의 후보가 없습니다.', $('duplicateStatus').value === 'PENDING' ? '모든 후보를 처리했습니다.' : '') + '</div>';
		}, {what: '중복 후보를'});
		target.querySelectorAll('[data-merge-approve]').forEach(button => button.onclick = () => approveMerge(duplicateItems.get(Number(button.dataset.mergeApprove))));
		target.querySelectorAll('[data-merge-reject]').forEach(button => button.onclick = () => rejectMerge(duplicateItems.get(Number(button.dataset.mergeReject))));
		bindOpenScholarship(target);
	}

	async function loadDuplicateReports() {
		const target = $('duplicateReports');
		await view.load(target, async () => {
			const page = await api(qs('/api/v1/scholarships/reports', {status: 'PENDING', reason: 'DUPLICATE', page: 0, size: 50, sort: 'createdAt,desc'}));
			$('dupReportCount').textContent = page.totalElements ? fmt.num(page.totalElements) : '';
			return page.content.length ? page.content.map(row => '<div class="list-row"><div class="grow"><button type="button" class="btn-link" data-report-detail="' +
				row.scholarshipId + '">장학금 #' + row.scholarshipId + ' ' + esc(row.scholarshipTitle || '') + '</button><span class="cell-sub">신고 #' + row.reportId +
				' · ' + esc(row.scholarshipProvider || '기관 없음') + ' · ' + esc(fmt.ago(row.createdAt)) + (row.detail ? ' · "' + esc(row.detail) + '"' : '') + '</span></div>' +
				'<div class="actions"><button type="button" class="btn btn-sm" data-pick-keep="' + row.scholarshipId + '" data-title="' + esc(row.scholarshipTitle || '') + '">유지할 쪽으로</button>' +
				'<button type="button" class="btn btn-sm" data-pick-drop="' + row.scholarshipId + '" data-title="' + esc(row.scholarshipTitle || '') + '">내릴 쪽으로</button></div></div>').join('')
				: view.emptyHtml('처리 대기 중인 중복 신고가 없습니다.');
		}, {what: '중복 신고를'});
		target.querySelectorAll('[data-report-detail]').forEach(button => button.onclick = () => openScholarshipPanel('duplicateReportDetail', button.dataset.reportDetail));
		bindPickButtons(target);
	}

	function bindPickButtons(root) {
		root.querySelectorAll('[data-pick-keep]').forEach(button => button.onclick = () => pickMergeSide('keep', button.dataset.pickKeep, button.dataset.title));
		root.querySelectorAll('[data-pick-drop]').forEach(button => button.onclick = () => pickMergeSide('drop', button.dataset.pickDrop, button.dataset.title));
	}

	function pickMergeSide(role, id, title) {
		$(role === 'keep' ? 'mergePrimaryId' : 'mergeDuplicateId').value = id;
		$(role === 'keep' ? 'mergePrimaryLabel' : 'mergeDuplicateLabel').textContent = '#' + id + ' ' + (title || '');
		showDupTab('manual');
		ui.toast((role === 'keep' ? '유지할 쪽' : '내릴 쪽') + '을 #' + id + '(으)로 골랐습니다.', 'info', 2500);
	}

	async function searchMergeScholarships() {
		const keyword = val('mergeSearch');
		const target = $('mergeSearchResults');
		if (!keyword) { target.innerHTML = view.emptyHtml('찾을 장학금명이나 기관을 입력하세요.'); return; }
		await view.load(target, async () => {
			const page = await api(qs('/api/v1/scholarships/admin/scholarships', {keyword, page: 0, size: 20, includeDeleted: false, sort: 'createdAt,desc'}));
			return page.content.length ? page.content.map(row => '<div class="list-row"><div class="grow"><button type="button" class="btn-link" data-open-scholarship="' +
				row.scholarshipId + '">#' + row.scholarshipId + ' ' + esc(row.title || '') + '</button><span class="cell-sub">' + esc(row.provider || '기관 없음') + ' · ' +
				esc(label('recruitment', row.recruitmentStatus)) + ' · ' + esc(row.source || 'MANUAL') + '</span></div><div class="actions">' +
				'<button type="button" class="btn btn-sm" data-pick-keep="' + row.scholarshipId + '" data-title="' + esc(row.title || '') + '">유지할 쪽</button>' +
				'<button type="button" class="btn btn-sm" data-pick-drop="' + row.scholarshipId + '" data-title="' + esc(row.title || '') + '">내릴 쪽</button></div></div>').join('')
				: view.emptyHtml('검색 결과가 없습니다.');
		}, {what: '장학금을'});
		bindPickButtons(target);
		bindOpenScholarship(target);
	}

	function showDupTab(name) {
		document.querySelectorAll('[data-dup-tab]').forEach(button => button.classList.toggle('active', button.dataset.dupTab === name));
		document.querySelectorAll('[data-dup-panel]').forEach(panel => panel.hidden = panel.dataset.dupPanel !== name);
		if (name === 'reports') loadDuplicateReports();
	}

	/* ---------- 이미지 */

	async function loadImages() {
		const target = $('imageGrid');
		await view.load(target, async () => {
			const page = await api(qs('/api/v1/scholarships/admin/images', {keyword: val('imageKeyword'), source: val('imageSource'),
				hasImage: $('imagePresence').value, page: pageNo.image, size: PAGE_SIZE, sort: 'createdAt,desc'}));
			renderPager('imagePager', page, 'image', loadImages);
			return page.content.length ? page.content.map(row => '<article class="image-card">' +
				(safeUrl(row.previewUrl) ? '<img src="' + esc(row.previewUrl) + '" alt="" loading="lazy">' : '<div class="image-empty">이미지 없음</div>') +
				'<button type="button" class="btn-link cell-title" style="text-align:left" data-open-scholarship="' + row.scholarshipId + '">#' + row.scholarshipId + ' ' +
				esc(row.scholarshipTitle || '') + '</button><span class="text-muted">' + esc(row.provider || '기관 없음') + ' · ' + esc(row.source || 'MANUAL') + '</span>' +
				(row.sourceUrl ? '<span class="text-muted truncate" title="' + esc(row.sourceUrl) + '">원본 ' + esc(row.sourceUrl) + '</span>' : '') +
				'<button type="button" class="btn btn-sm ' + (row.imageId ? '' : 'btn-primary') + '" data-image-edit="' + row.scholarshipId + '" data-title="' +
				esc(row.scholarshipTitle || '') + '" data-has-image="' + (row.imageId ? 'true' : 'false') + '">' + (row.imageId ? '이미지 교체' : '이미지 등록') + '</button></article>').join('')
				: '<div style="grid-column:1/-1">' + view.emptyHtml('조건에 맞는 장학금이 없습니다.') + '</div>';
		}, {what: '이미지 목록을'});
		target.querySelectorAll('[data-image-edit]').forEach(button => button.onclick = () =>
			openImageDialog(Number(button.dataset.imageEdit), button.dataset.title, button.dataset.hasImage === 'true'));
		bindOpenScholarship(target);
	}

	/* ---------- 신고·문의 */

	async function loadReports() {
		const body = $('reportRows');
		await view.load(body, async () => {
			const page = await api(qs('/api/v1/scholarships/reports', {status: $('reportStatus').value, reason: $('reportReason').value,
				keyword: val('reportKeyword'), page: pageNo.report, size: PAGE_SIZE, sort: 'createdAt,desc'}));
			renderPager('reportPager', page, 'report', loadReports);
			return page.content.length ? page.content.map(row => '<tr>' + cells([
				'<span class="nowrap">#' + row.reportId + '</span>',
				scholarshipLink(row.scholarshipId, '#' + row.scholarshipId + ' ' + (row.scholarshipTitle || '')) + '<span class="cell-sub">' + esc(row.scholarshipProvider || '기관 없음') + '</span>',
				'<div class="badges">' + (row.reasons || []).map(reason => '<span class="badge" title="' + esc(reason) + '">' + esc(label('reportReason', reason)) + '</span>').join('') + '</div>',
				'<span class="clamp-2" title="' + esc(row.detail || '') + '">' + esc(row.detail || '-') + '</span>',
				'<span class="nowrap">' + esc(fmt.ts(row.createdAt)) + '</span><span class="cell-sub' + (fmt.ageDays(row.createdAt) >= 7 ? ' text-danger' : '') + '">' + esc(fmt.ago(row.createdAt)) + '</span>',
				row.status === 'PENDING' ? '<div class="actions"><button type="button" class="btn btn-sm btn-primary" data-resolve="' + row.reportId + '">해결</button>' +
					'<button type="button" class="btn btn-sm" data-reject="' + row.reportId + '">반려</button>' +
					'<button type="button" class="btn btn-sm" data-edit-scholarship="' + row.scholarshipId + '">장학금 수정</button></div>'
					: badge('handle', row.status) + '<span class="cell-sub">' + esc(row.adminNote || '-') + '</span>'
			]) + '</tr>').join('') : view.row(6, view.emptyHtml('이 조건의 신고가 없습니다.'));
		}, {colspan: 6, what: '신고 목록을'});
		const find = id => ({kind: 'report', id});
		body.querySelectorAll('[data-resolve]').forEach(b => b.onclick = () => openResolution(find(b.dataset.resolve), 'RESOLVED', b.closest('tr')));
		body.querySelectorAll('[data-reject]').forEach(b => b.onclick = () => openResolution(find(b.dataset.reject), 'REJECTED', b.closest('tr')));
		body.querySelectorAll('[data-edit-scholarship]').forEach(b => b.onclick = () => openEdit({scholarshipId: Number(b.dataset.editScholarship)}));
		bindOpenScholarship(body);
	}

	async function loadInquiries() {
		const body = $('inquiryRows');
		await view.load(body, async () => {
			const page = await api(qs('/api/v1/admin/content-inquiries', {status: $('inquiryStatus').value, type: $('inquiryType').value,
				keyword: val('inquiryKeyword'), page: pageNo.inquiry, size: PAGE_SIZE, sort: 'createdAt,desc'}));
			renderPager('inquiryPager', page, 'inquiry', loadInquiries);
			return page.content.length ? page.content.map(row => '<tr>' + cells([
				'<span class="nowrap">#' + row.inquiryId + '</span>',
				'<b>' + esc(label('inquiryType', row.inquiryType || 'OTHER')) + '</b><span class="cell-sub">' + esc(row.inquiryTarget || '-') + '</span>',
				esc(row.organizationName || '-') + '<span class="cell-sub">' + esc(row.email || '') + (row.phone ? ' · ' + esc(row.phone) : '') + '</span>',
				'<span class="clamp-2" title="' + esc(row.content || '') + '">' + esc(row.content || '-') + '</span>' +
					(safeUrl(row.attachmentUrl) ? link(row.attachmentUrl, row.attachmentName || '첨부파일', 'btn-link') : ''),
				'<span class="nowrap">' + esc(fmt.ts(row.createdAt)) + '</span><span class="cell-sub' + (fmt.ageDays(row.createdAt) >= 7 ? ' text-danger' : '') + '">' + esc(fmt.ago(row.createdAt)) + '</span>',
				row.status === 'PENDING' ? '<div class="actions"><button type="button" class="btn btn-sm btn-primary" data-resolve="' + row.inquiryId + '">해결</button>' +
					'<button type="button" class="btn btn-sm" data-reject="' + row.inquiryId + '">반려</button></div>'
					: badge('handle', row.status) + '<span class="cell-sub">' + esc(row.adminNote || '-') + '</span>'
			]) + '</tr>').join('') : view.row(6, view.emptyHtml('이 조건의 문의가 없습니다.'));
		}, {colspan: 6, what: '문의 목록을'});
		body.querySelectorAll('[data-resolve]').forEach(b => b.onclick = () => openResolution({kind: 'inquiry', id: b.dataset.resolve}, 'RESOLVED', b.closest('tr')));
		body.querySelectorAll('[data-reject]').forEach(b => b.onclick = () => openResolution({kind: 'inquiry', id: b.dataset.reject}, 'REJECTED', b.closest('tr')));
	}

	let reportTab = 'report';
	function showReportTab(name) {
		reportTab = name;
		document.querySelectorAll('[data-report-tab]').forEach(button => button.classList.toggle('active', button.dataset.reportTab === name));
		document.querySelectorAll('[data-report-panel]').forEach(panel => panel.hidden = panel.dataset.reportPanel !== name);
		return name === 'inquiry' ? loadInquiries() : loadReports();
	}

	/* ---------- 시스템·배치·감사 */

	async function loadSystem() {
		const target = $('systemStats');
		await view.load(target, async () => {
			const data = await api('/api/v1/admin/system/status');
			fmt.calibrate(data.checkedAt);
			$('systemCheckedAt').textContent = '확인 ' + fmt.tsKst(data.checkedAt);
			const check = (name, c) => '<div class="stat"><div class="label">' + name + '</div><div class="value ' + (c.status === 'UP' ? 'text-success' : 'text-danger') + '">' +
				(c.status === 'UP' ? '정상' : esc(c.status)) + '</div><div class="sub">' + esc(c.detail || '') + ' · ' + c.latencyMs + 'ms</div></div>';
			const usage = (name, percent, used, total) => '<div class="stat"><div class="label">' + name + '</div><div class="value ' +
				(percent >= 90 ? 'text-danger' : percent >= 75 ? 'text-warning' : '') + '">' + percent + '%</div><div class="sub">' + fmt.bytes(used) + ' / ' + fmt.bytes(total) + '</div></div>';
			return check('애플리케이션', data.application) + check('PostgreSQL', data.database) + check('Redis', data.redis) +
				usage('JVM 메모리(Heap)', data.jvmHeap.usedPercent, data.jvmHeap.usedBytes, data.jvmHeap.maxBytes) +
				usage('서버 디스크', data.disk.usedPercent, data.disk.usedBytes, data.disk.totalBytes);
		}, {what: '시스템 상태를'});
	}

	async function loadLogs() {
		const target = $('appLogs');
		await view.load(target, async () => {
			const data = await api(qs('/api/v1/admin/system/logs', {lines: $('logLines').value, level: $('logLevel').value, keyword: val('logKeyword')}));
			if (!data.available) return '<div class="card-body">' + view.emptyHtml('로그를 읽을 수 없습니다.', data.message) + '</div>';
			$('logMeta').textContent = data.lines.length + '줄 · ' + fmt.tsKst(data.checkedAt) + ' 기준';
			if (!data.lines.length) return '<div class="card-body">' + view.emptyHtml('조건에 맞는 로그가 없습니다.', '수준을 [전체]로 바꾸거나 검색어를 지워 보세요.') + '</div>';
			return '<div class="raw dark">' + data.lines.map(line => /\bERROR\b/.test(line) ? '<span class="log-error">' + esc(line) + '</span>'
				: /\bWARN\b/.test(line) ? '<span class="log-warn">' + esc(line) + '</span>' : esc(line)).join('\n') + '</div>';
		}, {what: '로그를'});
	}

	async function loadJobs() {
		const body = $('jobRows');
		await view.load(body, async () => {
			const page = await api(qs('/api/v1/admin/jobs', {page: pageNo.jobs, size: PAGE_SIZE}));
			renderPager('jobPager', page, 'jobs', loadJobs);
			return page.content.length ? page.content.map(row => '<tr data-job="' + row.id + '">' + cells([
				'#' + row.id,
				esc(label('jobType', row.jobType)) + '<span class="cell-sub mono">' + esc(row.jobType) + '</span>',
				esc(label('trigger', row.trigger)),
				badge('jobStatus', row.status),
				'<span class="nowrap">' + esc(fmt.ts(row.startedAt)) + '</span>',
				'<span class="nowrap">' + esc(fmt.ts(row.finishedAt)) + '</span>',
				'<span class="nowrap">' + esc(jobDuration(row)) + '</span>',
				'<span class="clamp-2" title="' + esc(row.errorMessage || row.summary || '') + '">' + esc(row.errorMessage || row.summary || '-') + '</span>'
			]) + '</tr>').join('') : view.row(8, view.emptyHtml('기록된 배치가 없습니다.'));
		}, {colspan: 8, what: '배치 이력을'});
	}

	function jobDuration(row) {
		if (!row.startedAt) return '-';
		const start = Date.parse(String(row.startedAt).slice(0, 23) + 'Z');
		const end = row.finishedAt ? Date.parse(String(row.finishedAt).slice(0, 23) + 'Z') : null;
		if (!end) return row.status === 'RUNNING' ? '실행 중' : '-';
		const minutes = Math.round((end - start) / 60000);
		return minutes < 1 ? '1분 미만' : minutes < 60 ? minutes + '분' : Math.floor(minutes / 60) + '시간 ' + (minutes % 60) + '분';
	}

	let auditRows = new Map();
	async function loadAudit() {
		const body = $('auditRows');
		await view.load(body, async () => {
			const rows = await api(qs('/api/v1/admin/audit-log', {action: $('auditAction').value, size: $('auditSize').value}));
			auditRows = new Map(rows.map(row => [row.id, row]));
			return rows.length ? rows.map(row => '<tr>' + cells([
				'<span class="nowrap">' + esc(fmt.ts(row.createdAt)) + '</span>',
				'<span class="mono" title="' + esc(row.actorId || '') + '">' + esc(String(row.actorId || '-').slice(0, 8)) + '</span>',
				'<span class="badge b-brand" title="' + esc(row.action) + '">' + esc(label('action', row.action)) + '</span>' +
					(row.restoredAt ? '<span class="cell-sub text-success">복구됨 ' + esc(fmt.ts(row.restoredAt)) + '</span>' : ''),
				row.targetType === 'SCHOLARSHIP' && row.targetId ? scholarshipLink(row.targetId, '장학금 #' + row.targetId)
					: esc(row.targetType ? label('targetType', row.targetType) + ' #' + row.targetId : '-'),
				'<span class="clamp-2" title="' + esc(row.detail || '') + '">' + esc(row.detail || '-') + '</span>',
				'<div class="actions">' + (row.beforeJson || row.afterJson
					? '<button type="button" class="btn btn-sm" data-audit-diff="' + row.id + '">전후 비교</button>'
					: '<span class="text-muted text-sm" title="이 작업은 바뀐 값을 저장하지 않습니다">비교 기록 없음</span>') +
					(row.restorable && !row.restoredAt ? '<button type="button" class="btn btn-sm btn-danger-ghost" data-audit-restore="' + row.id + '">복구</button>' : '') + '</div>'
			]) + '</tr>').join('') : view.row(6, view.emptyHtml('기록이 없습니다.'));
		}, {colspan: 6, what: '감사 기록을'});
		body.querySelectorAll('[data-audit-diff]').forEach(b => b.onclick = () => showAuditDiff(auditRows.get(Number(b.dataset.auditDiff))));
		body.querySelectorAll('[data-audit-restore]').forEach(b => b.onclick = () => restoreAudit(auditRows.get(Number(b.dataset.auditRestore))));
		bindOpenScholarship(body);
	}

	function parseJson(text) { try { return JSON.parse(text); } catch (ignored) { return text; } }
	const showValue = value => value == null || value === '' ? '(비어 있음)' : typeof value === 'object' ? JSON.stringify(value, null, 1) : String(value);

	/** 전후 비교: 바뀐 필드만 표로. 스냅샷이 객체가 아니면 원문 그대로 보여 준다. */
	function showAuditDiff(row) {
		const before = parseJson(row.beforeJson), after = parseJson(row.afterJson);
		let html;
		if (before && after && typeof before === 'object' && typeof after === 'object') {
			const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])];
			const changed = keys.filter(key => JSON.stringify(before[key]) !== JSON.stringify(after[key]));
			html = changed.length ? '<table class="diff-table"><thead><tr><th>필드</th><th>이전</th><th>이후</th></tr></thead><tbody>' +
				changed.map(key => '<tr><td class="mono">' + esc(key) + '</td><td class="val before">' + esc(showValue(before[key])) +
					'</td><td class="val after">' + esc(showValue(after[key])) + '</td></tr>').join('') + '</tbody></table>'
				: view.emptyHtml('바뀐 필드가 없습니다.');
		} else {
			html = '<div class="grid-2"><div><p class="section-label">이전</p><div class="raw">' + esc(showValue(before)) + '</div></div>' +
				'<div><p class="section-label">이후</p><div class="raw">' + esc(showValue(after)) + '</div></div></div>';
		}
		ui.notify(label('action', row.action) + ' · ' + fmt.tsKst(row.createdAt), '<p class="text-sm text-muted" style="margin-bottom:var(--sp-3)">' +
			esc(row.detail || '') + '</p>' + html);
	}

	/* ================================================================== 쓰기 작업 */

	const REPORT_REASONS = ['ALREADY_CLOSED', 'WRONG_INFO', 'WRONG_CONDITION', 'DUPLICATE', 'OTHER'];

	/** 관리자가 데이터 이상을 발견한 자리에서 바로 신고를 남긴다(화면 이동 없음). */
	function reportScholarship(s) {
		const body = '<div class="target-box"><span class="target-id">장학금 #' + s.id + '</span><span class="target-title">' + esc(s.title || '') + '</span></div>' +
			'<p class="section-label">신고 사유 <span class="req">*</span></p><div class="choice-list">' + REPORT_REASONS.map(code =>
				'<label class="choice"><input type="radio" name="reason" value="' + code + '"><span>' + esc(label('reportReason', code)) + '</span></label>').join('') + '</div>' +
			'<div class="form-field" style="margin-top:var(--sp-3)"><label for="reportDetail">무엇이 잘못됐는지 <span class="text-muted">(선택, 200자)</span></label>' +
			'<textarea id="reportDetail" class="textarea" maxlength="200"></textarea></div>';
		return ui.modal({
			title: '신고 남기기', subtitle: '[신고·문의 처리] 대기열에 올라가 다른 관리자가 확인합니다.', body, confirmLabel: '신고 등록',
			valid: root => Boolean(root.querySelector('[name="reason"]:checked')),
			onConfirm: async root => {
				const reason = root.querySelector('[name="reason"]:checked').value;
				await api('/api/v1/scholarships/' + s.id + '/reports', {method: 'POST',
					body: {reasons: [reason], detail: textOrNull(root.querySelector('#reportDetail').value)}});
				ui.toast('신고를 남겼습니다. [신고·문의 처리]에서 확인할 수 있습니다.');
				afterWrite({});
			}
		});
	}

	function openResolution(target, status, row) {
		const isReport = target.kind === 'report';
		const title = row ? row.querySelector('td:nth-child(2)').textContent.trim() : '';
		const body = '<div class="target-box"><span class="target-id">' + (isReport ? '신고' : '문의') + ' #' + esc(target.id) + '</span><span class="target-title">' +
			esc(title) + '</span></div><p class="section-label">처리 결과 <span class="req">*</span></p><div class="choice-list">' +
			'<label class="choice"><input type="radio" name="status" value="RESOLVED"' + (status === 'RESOLVED' ? ' checked' : '') + '><span>해결<small>실제로 수정·병합·게시 중단을 했습니다.</small></span></label>' +
			'<label class="choice"><input type="radio" name="status" value="REJECTED"' + (status === 'REJECTED' ? ' checked' : '') + '><span>반려<small>원문과 일치하거나 별도 공고라 고칠 것이 없습니다.</small></span></label></div>' +
			'<div class="form-field" style="margin-top:var(--sp-3)"><label for="resolveNote">답변 <span class="req">*</span></label><textarea id="resolveNote" class="textarea" maxlength="500"></textarea>' +
			'<span class="field-help">' + (isReport ? '신고한 사용자의 \'내 신고\' 목록에 보입니다. 해결: 실제로 바꾼 내용 / 반려: 그렇게 판단한 근거를 적습니다.'
				: '기관·권리자에게 회신할 때 쓰입니다. 실제 조치 결과를 구체적으로 적습니다.') + '</span></div>';
		return ui.modal({
			title: isReport ? '장학금 신고 처리' : '콘텐츠 이용문의 처리', body, confirmLabel: '처리 완료',
			footNote: '처리 후에는 대기 목록에서 빠집니다.',
			valid: root => Boolean(root.querySelector('[name="status"]:checked')) && root.querySelector('#resolveNote').value.trim().length > 0,
			onConfirm: async root => {
				const chosen = root.querySelector('[name="status"]:checked').value;
				const path = isReport ? '/api/v1/scholarships/reports/' + target.id : '/api/v1/admin/content-inquiries/' + target.id;
				await api(path, {method: 'PATCH', body: {status: chosen, adminNote: root.querySelector('#resolveNote').value.trim()}});
				ui.toast((isReport ? '신고 #' : '문의 #') + target.id + '을(를) ' + label('handle', chosen) + ' 처리했습니다.');
				afterWrite({reload: isReport ? loadReports : loadInquiries});
			}
		});
	}

	function approveMerge(item) {
		const p = item.primary, d = item.duplicate;
		return ui.confirmAction({
			title: '중복 장학금 병합', subtitle: '후보 #' + item.candidateId, confirmLabel: '병합 실행',
			targets: [{id: '유지할 쪽 #' + p.scholarshipId, title: p.title, meta: p.provider}, {id: '내릴 쪽 #' + d.scholarshipId, title: d.title, meta: d.provider}],
			summary: ['#' + esc(d.scholarshipId) + '이(가) 사용자 목록에서 내려갑니다.',
				'#' + esc(d.scholarshipId) + '의 스크랩·자소서 등 사용자 데이터가 #' + esc(p.scholarshipId) + '(으)로 옮겨집니다.',
				'#' + esc(p.scholarshipId) + '의 본문은 바뀌지 않습니다. 필요하면 병합 후 통합 수정하세요.'],
			reversible: 'no', reversibleText: '옮겨진 사용자 데이터를 원래대로 나눌 수 없고, 병합으로 내린 쪽은 복원되지 않습니다.',
			onConfirm: async () => {
				await api('/api/v1/scholarships/merge/candidates/' + item.candidateId + '/approve', {method: 'POST'});
				ui.toast('#' + d.scholarshipId + '을(를) #' + p.scholarshipId + '(으)로 병합했습니다.');
				afterWrite({reload: loadDuplicates});
			}
		});
	}

	function rejectMerge(item) {
		return ui.confirmAction({
			title: '중복 아님으로 반려', subtitle: '후보 #' + item.candidateId, kind: 'primary', confirmLabel: '반려',
			targets: [{id: '#' + item.primary.scholarshipId, title: item.primary.title}, {id: '#' + item.duplicate.scholarshipId, title: item.duplicate.title}],
			summary: ['두 장학금을 그대로 둡니다. 이 쌍은 다시 자동 후보로 올라오지 않습니다.'],
			reversible: 'yes', reversibleText: '반려한 후보는 나중에 다시 승인 대기로 되돌릴 수 있습니다.',
			reason: {label: '중복이 아닌 근거', required: true, placeholder: '예: 학기가 다른 별개 공고, 캠퍼스가 다름', help: '다른 관리자가 같은 쌍을 다시 볼 때 참고합니다.'},
			onConfirm: async note => {
				await api('/api/v1/scholarships/merge/candidates/' + item.candidateId + '/reject', {method: 'POST', body: {note}});
				ui.toast('후보 #' + item.candidateId + '을(를) 반려했습니다.');
				afterWrite({reload: loadDuplicates});
			}
		});
	}

	async function createManualMerge(button) {
		const primary = Number($('mergePrimaryId').value), duplicate = Number($('mergeDuplicateId').value);
		['mergePrimaryId', 'mergeDuplicateId'].forEach(id => $(id).classList.toggle('is-invalid', !Number($(id).value)));
		if (!primary || !duplicate) { ui.toast('유지할 장학금과 내릴 장학금 ID를 모두 입력하세요.', 'error'); return; }
		if (primary === duplicate) { ui.toast('같은 장학금끼리는 후보로 만들 수 없습니다.', 'error'); return; }
		await ui.busy(button, async () => {
			try {
				await api('/api/v1/scholarships/merge/candidates/manual', {method: 'POST',
					body: {primaryScholarshipId: primary, duplicateScholarshipId: duplicate, reason: textOrNull($('mergeReason').value)}});
			} catch (error) {
				ui.toast(WC.errorText(error), 'error');
				return;
			}
			ui.toast('#' + primary + ' ← #' + duplicate + ' 후보를 승인 대기에 올렸습니다.');
			['mergePrimaryId', 'mergeDuplicateId', 'mergeReason'].forEach(id => $(id).value = '');
			$('mergePrimaryLabel').textContent = ''; $('mergeDuplicateLabel').textContent = '';
			afterWrite({});
		}, '추가 중…');
	}

	async function confirmAlwaysOpen(button) {
		await ui.busy(button, async () => {
			try {
				await api('/api/v1/scholarships/admin/always-open/' + button.dataset.alwaysConfirm + '/confirm', {method: 'PATCH'});
				ui.toast('#' + button.dataset.alwaysConfirm + ' 원문 확인 시각을 기록했습니다.');
				afterWrite({reload: loadAlways});
			} catch (error) {
				ui.toast(WC.errorText(error), 'error');
			}
		}, '기록 중…');
	}

	function closeAlwaysOpen(id, title) {
		return ui.confirmAction({
			title: '상시모집 마감 처리', confirmLabel: '마감 처리',
			targets: [{id: '장학금 #' + id, title}],
			summary: ['모집 상태: 상시모집 → <b>마감</b>', '사용자 목록에서 마감된 공고로 보입니다.'],
			reversible: 'yes', reversibleText: '[감사·복구] 또는 통합 수정에서 모집 상태를 다시 바꿀 수 있습니다.',
			extraHtml: '<div class="notice neutral">원문에서 모집이 실제로 끝났는지 먼저 확인하세요.</div>',
			onConfirm: async () => {
				await api('/api/v1/scholarships/manual/' + id, {method: 'PATCH', body: {recruitmentStatus: 'CLOSED'}});
				ui.toast('#' + id + '을(를) 마감 처리했습니다.');
				afterWrite({scholarshipId: id, reload: loadAlways});
			}
		});
	}

	function retryFailures() {
		const ids = selectedRetryIds();
		if (!ids.length) return;
		return ui.confirmAction({
			title: 'LLM 재처리', kind: 'primary', confirmLabel: ids.length + '건 재처리',
			targets: [{id: '원문 ' + ids.length + '건', title: ids.slice(0, 12).map(id => '#' + id).join(', ') + (ids.length > 12 ? ' 외 ' + (ids.length - 12) + '건' : '')}],
			summary: ['선택한 대학 공지 원문을 LLM 으로 다시 파싱합니다.', '<b>건마다 LLM 비용이 발생합니다.</b> 크레딧이 부족하면 다시 실패합니다.', '한 번에 최대 100건, 완료까지 몇 분 걸릴 수 있습니다.'],
			reversible: 'partial', reversibleText: '만들어진 장학금은 고치거나 내릴 수 있지만, 쓴 비용은 돌아오지 않습니다.',
			onConfirm: async () => {
				const result = await api(qs('/api/v1/scholarships/parse/univ-llm', {limit: Math.min(ids.length, 100), reparse: true, dryRun: false,
					rawIds: ids.slice(0, 100).join(','), skipComplete: false}), {method: 'POST'});
				ui.toast('재처리 완료 · 성공 ' + result.parsedCount + ' · 건너뜀 ' + result.skippedCount + ' · 실패 ' + result.failedCount,
					result.failedCount ? 'warn' : 'success');
				afterWrite({reload: loadFailures});
			}
		});
	}

	function openImageDialog(id, title, hasImage) {
		const body = '<div class="target-box"><span class="target-id">장학금 #' + id + '</span><span class="target-title">' + esc(title) + '</span></div>' +
			'<div class="form-field"><label for="imageUrl">이미지 주소(URL)</label><input id="imageUrl" class="input" type="url" placeholder="https://…">' +
			'<span class="field-help">공고 원문의 포스터 주소를 붙여 넣거나, 아래에서 파일을 고르세요(둘 중 하나).</span></div>' +
			'<div class="form-field" style="margin-top:var(--sp-3)"><label for="imageFile">이미지 파일</label><input id="imageFile" type="file" accept="image/png,image/jpeg,image/gif,image/webp">' +
			'<span class="field-help">5MB 이하 PNG·JPG·GIF·WEBP</span><span class="field-error" data-file-error></span></div>' +
			(hasImage ? '<div class="revert no" style="margin-top:var(--sp-3)"><b>기존 이미지를 바꿉니다.</b> 이전 이미지로 되돌리는 기능은 없습니다.</div>' : '');
		return ui.modal({
			title: hasImage ? '이미지 교체' : '이미지 등록', body, confirmLabel: hasImage ? '교체' : '등록', kind: hasImage ? 'danger' : 'primary',
			valid: root => Boolean(root.querySelector('#imageUrl').value.trim() || root.querySelector('#imageFile').files[0]),
			onConfirm: async root => {
				const file = root.querySelector('#imageFile').files[0], url = root.querySelector('#imageUrl').value.trim();
				if (file) {
					const form = new FormData();
					form.append('file', file);
					await api('/api/v1/scholarships/admin/scholarships/' + id + '/image-file', {method: 'PUT', form});
				} else {
					await api('/api/v1/scholarships/admin/scholarships/' + id + '/image-url', {method: 'PUT', body: {imageUrl: url}});
				}
				ui.toast('#' + id + ' 이미지를 저장했습니다.');
				afterWrite({scholarshipId: id, reload: loadImages});
			}
		});
	}

	function restoreAudit(row) {
		return ui.confirmAction({
			title: '변경 이전으로 복구', confirmLabel: '복구',
			targets: [{id: label('targetType', row.targetType, '대상') + ' #' + row.targetId, title: label('action', row.action) + ' · ' + fmt.tsKst(row.createdAt)}],
			summary: ['이 기록 직전 값으로 되돌립니다. 모집 상태와 기록 이후 다시 바뀐 필드는 제외됩니다.'],
			reversible: 'yes', reversibleText: '복구도 감사 기록으로 남아 다시 되돌릴 수 있습니다.',
			onConfirm: async () => {
				await api('/api/v1/admin/audit-log/' + row.id + '/restore', {method: 'PATCH'});
				ui.toast('기록 #' + row.id + ' 이전 값으로 복구했습니다.');
				afterWrite({scholarshipId: row.targetId, reload: loadAudit});
			}
		});
	}

	/* ---------- 내리기·복원 */

	/**
	 * 내리기: 먼저 delete-check 로 걸려 있는 사용자 데이터와 중복 의심을 보여 준다.
	 * 중복이면 병합이 맞다 — 병합은 스크랩·자소서를 남길 쪽으로 옮기지만 내리기는 옮기지 않는다.
	 */
	async function takedownScholarship(s, button) {
		let check;
		try {
			check = await ui.busy(button, () => api('/api/v1/scholarships/admin/scholarships/' + s.id + '/delete-check'), '확인 중…');
		} catch (error) {
			ui.toast(WC.errorText(error), 'error');
			return;
		}
		if (!check) return;
		if (check.deleted) {
			ui.toast('이미 내린 장학금입니다. 목록을 새로고침하세요.', 'warn');
			afterWrite({scholarshipId: s.id, reload: reloadCurrentList});
			return;
		}
		const essays = check.essayNotStartedCount + check.essayInProgressCount + check.essayCompletedCount;
		const pending = (check.mergeCandidates || []).filter(c => c.status === 'PENDING');
		const similar = check.similarScholarships || [];
		const mergeBlock = check.mergeSuggested ? '<div class="notice warn"><b>중복이라면 내리기 대신 병합을 사용하세요.</b><br>' +
			'병합은 스크랩·자소서를 남길 장학금으로 옮기지만, 내리기는 옮기지 않아 사용자 목록에서 그냥 사라집니다.' +
			(pending.length ? '<p class="section-label" style="margin-top:var(--sp-2)">승인 대기 중인 중복 후보</p>' + pending.map(c =>
				'<div class="list-row" style="padding-left:0;padding-right:0"><div class="grow">후보 #' + c.candidateId + ' · 유지 #' + c.primaryId + ' ' +
				esc(c.primaryTitle || '') + ' ← 내릴 쪽 #' + c.duplicateId + ' ' + esc(c.duplicateTitle || '') + '</div>' +
				'<button type="button" class="btn btn-sm" data-go-candidate="' + c.candidateId + '">이 후보 검토하기</button></div>').join('') : '') +
			(similar.length ? '<p class="section-label" style="margin-top:var(--sp-2)">제목이 같은 다른 공고</p>' + similar.map(x =>
				'<div class="list-row" style="padding-left:0;padding-right:0"><div class="grow">#' + x.scholarshipId + ' ' + esc(x.title || '') +
				'<span class="cell-sub">' + esc(x.provider || '기관 없음') + ' · ' + esc(label('recruitment', x.recruitmentStatus)) + ' · 마감 ' +
				esc(fmt.biz(x.applicationEndAt, true)) + '</span></div><button type="button" class="btn btn-sm" data-go-similar="' + x.scholarshipId +
				'" data-title="' + esc(x.title || '') + '">이 공고로 병합 후보 만들기</button></div>').join('') : '') + '</div>' : '';
		const warnings = (check.warnings || []).length ? '<div class="notice neutral"><ul>' + check.warnings.map(w => '<li>' + esc(w) + '</li>').join('') + '</ul></div>' : '';
		return ui.confirmAction({
			title: '장학금 내리기', confirmLabel: '내리기',
			targets: [{id: '장학금 #' + s.id, title: s.title, meta: s.provider}],
			summary: ['사용자 목록·검색·추천에서 보이지 않게 됩니다.',
				'이 장학금을 스크랩한 사용자 <b>' + fmt.num(check.scrapCount) + '명</b>의 스크랩 목록에서도 보이지 않습니다.',
				'연결된 자소서 <b>' + fmt.num(essays) + '건</b> (시작 전 ' + fmt.num(check.essayNotStartedCount) + ' · 작성 중 ' +
					fmt.num(check.essayInProgressCount) + ' · 완료 ' + fmt.num(check.essayCompletedCount) + ')은 지워지지 않지만 다른 장학금으로 옮겨지지도 않습니다.',
				'다음 날 수집 배치가 다시 살리지 않습니다.'],
			extraHtml: mergeBlock + warnings,
			reversible: 'yes', reversibleText: '[전체 장학금]에서 "내린 장학금 포함"을 켜고 [복원]을 누르면 되돌릴 수 있습니다.',
			reason: {label: '내리는 사유', required: true, placeholder: '예: 장학금이 아닌 행사 안내 공고, 모집 취소 공고', help: '감사 기록과 장학금에 남고, 삭제 포함 목록에 보입니다.'},
			onOpen: (root, ctx) => {
				root.querySelectorAll('[data-go-candidate]').forEach(b => b.onclick = () => {
					ctx.close(null);
					showPage('duplicate', {load: false});
					showDupTab('review');
					$('duplicateStatus').value = 'PENDING';
					$('duplicateKeyword').value = s.title || '';
					pageNo.duplicate = 0;
					loadDuplicates();
				});
				root.querySelectorAll('[data-go-similar]').forEach(b => b.onclick = () => {
					ctx.close(null);
					showPage('duplicate', {load: false});
					pickMergeSide('keep', b.dataset.goSimilar, b.dataset.title);
					pickMergeSide('drop', String(s.id), s.title);
				});
			},
			onConfirm: async reason => {
				await api('/api/v1/scholarships/manual/' + s.id, {method: 'DELETE', body: {reason}});
				ui.toast('#' + s.id + ' 장학금을 내렸습니다. "내린 장학금 포함" 목록에서 복원할 수 있습니다.');
				afterWrite({scholarshipId: s.id, reload: reloadCurrentList});
			}
		});
	}

	function restoreScholarship(row) {
		if (!row) return;
		if (row.deleteKind === 'MERGE') {
			ui.notify('복원할 수 없습니다', '<div class="notice danger">병합으로 내린 장학금은 스크랩·자소서가 이미 다른 장학금으로 옮겨져 복원할 수 없습니다.</div>');
			return;
		}
		return ui.confirmAction({
			title: '장학금 복원', kind: 'primary', confirmLabel: '복원',
			targets: [{id: '장학금 #' + row.scholarshipId, title: row.title, meta: row.provider}],
			summary: ['사용자 목록에 다시 보입니다. 노출 여부는 현재 모집 상태를 따릅니다(마감이면 마감 공고로 보임).',
				row.deletedAt ? '내린 시각: ' + esc(fmt.tsKst(row.deletedAt)) + (row.deletedByName ? ' · ' + esc(row.deletedByName) : '') : '',
				row.deleteReason ? '내린 사유: ' + esc(row.deleteReason) : ''].filter(Boolean),
			reversible: 'yes', reversibleText: '복원한 뒤에도 다시 내릴 수 있습니다.',
			reason: {label: '복원 사유', required: false, placeholder: '예: 잘못 내림, 모집 재개'},
			onConfirm: async reason => {
				await api('/api/v1/scholarships/manual/' + row.scholarshipId + '/restore', {method: 'POST', body: {reason: reason || null}});
				ui.toast('#' + row.scholarshipId + ' 장학금을 복원했습니다.');
				afterWrite({scholarshipId: row.scholarshipId, reload: reloadCurrentList});
			}
		});
	}

	/* ---------- 수기 등록 폼 */

	function resetManualForm() {
		$('manualFields').innerHTML = fieldsHtml({scholarshipType: 'EXTERNAL'}, 'create');
		bindStatusWarning($('manualFields'));
		$('manualConditions').innerHTML = '';
		$('manualDocuments').innerHTML = '';
	}

	async function submitManual(event) {
		event.preventDefault();
		const form = $('manualForm');
		let payload;
		try {
			payload = collectForm(form, 'create');
		} catch (error) {
			ui.toast(error.message, 'error');
			return;
		}
		payload.source = {sourceUrl: payload.detailUrl, rawHtml: textOrNull(form.querySelector('[name="rawHtml"]').value)};
		await ui.busy($('manualSubmit'), async () => {
			try {
				const result = await api('/api/v1/scholarships/manual/full', {method: 'POST', body: payload});
				ui.toast('장학금 #' + result.scholarshipId + ' 등록 완료 · 조건 ' + result.conditionCount + '개 · 서류 ' + result.documentCount + '개');
				if (result.imageError) ui.toast('장학금은 등록했지만 이미지는 저장하지 못했습니다: ' + result.imageError, 'warn');
				resetManualForm();
				afterWrite({});
			} catch (error) {
				ui.toast(WC.errorText(error), 'error');
			}
		}, '등록 중…');
	}

	/* ---------- 엑셀 */

	function excelResultHtml(result, dryRun, applied) {
		const errors = result.errors || [];
		return '<div class="notice ' + (result.errorCount ? 'danger' : dryRun ? 'neutral' : 'success') + '"><b>' + (dryRun ? '검사 결과(아직 반영 안 됨)' : '반영 완료') + '</b> · 대상 ' +
			fmt.num(result.totalRows) + '행 · ' + (dryRun ? '반영 예정 ' : '반영 ') + fmt.num(applied) + '행 · 오류 ' + fmt.num(result.errorCount) + '행' +
			(dryRun && !result.errorCount && applied ? '<br>문제가 없습니다. [실제 반영]을 누르면 DB 에 저장됩니다.' : '') +
			(result.errorCount ? '<br>오류를 고친 파일로 다시 검사하세요. 오류가 있으면 반영할 수 없습니다.' : '') + '</div>' +
			(errors.length ? '<div class="table-wrap" style="max-height:280px"><table><thead><tr><th class="nowrap">엑셀 행</th><th>임시키</th><th>오류</th></tr></thead><tbody>' +
				errors.map(e => '<tr><td>' + e.rowNumber + '</td><td>' + esc(e.clientKey || '-') + '</td><td>' + esc(e.reason) + '</td></tr>').join('') + '</tbody></table></div>' : '');
	}

	function excelFlow({fileId, dryRunId, applyId, resultId, path, appliedKey, applyTitle, applySummary}) {
		const apply = $(applyId), result = $(resultId);
		const run = async dryRun => {
			const file = $(fileId).files[0];
			if (!file) { ui.toast('.xlsx 파일을 먼저 고르세요.', 'error'); return null; }
			const form = new FormData();
			form.append('file', file);
			const data = await api(path + '?dryRun=' + dryRun, {method: 'POST', form});
			result.innerHTML = excelResultHtml(data, dryRun, data[appliedKey]);
			apply.disabled = !(dryRun && data[appliedKey] > 0 && data.errorCount === 0);
			return data;
		};
		$(fileId).addEventListener('change', () => { apply.disabled = true; result.innerHTML = ''; });
		$(dryRunId).onclick = () => ui.busy($(dryRunId), () => run(true).catch(error => { result.innerHTML = WC.errorNotice(error); }), '검사 중…');
		apply.onclick = () => ui.confirmAction({
			title: applyTitle, confirmLabel: '실제 반영',
			targets: [{id: '파일', title: $(fileId).files[0] ? $(fileId).files[0].name : ''}],
			summary: applySummary,
			reversible: 'partial', reversibleText: '한 건씩 감사·복구나 통합 수정으로 고칠 수는 있지만, 한 번에 되돌리는 기능은 없습니다.',
			onConfirm: async () => {
				const data = await run(false);
				if (data) { ui.toast('엑셀을 반영했습니다 · ' + fmt.num(data[appliedKey]) + '행'); afterWrite({}); }
				apply.disabled = true;
			}
		});
	}

	/* ================================================================== 화면 이동 */

	const PAGES = {
		dashboard: {title: '대시보드', load: loadDashboard},
		intake: {title: '신규 수집', load: loadIntake},
		failures: {title: '실패 재처리', load: loadFailures},
		anomaly: {title: '데이터 이상', load: loadAnomalies},
		all: {title: '전체 장학금', load: loadScholarships},
		always: {title: '상시모집', load: loadAlways},
		duplicate: {title: '중복 판정', load: loadDuplicates},
		images: {title: '이미지 관리', load: loadImages},
		reports: {title: '신고·문의 처리', load: () => showReportTab(reportTab)},
		excel: {title: '수기·엑셀 등록', load: null},
		system: {title: '시스템 상태', load: () => Promise.all([loadSystem(), loadLogs()])},
		batches: {title: '배치 실행 이력', load: loadJobs},
		audit: {title: '감사·복구', load: loadAudit}
	};

	function showPage(id, {load = true} = {}) {
		if (!PAGES[id]) id = 'dashboard';
		currentPage = id;
		document.querySelectorAll('.page').forEach(page => page.classList.toggle('active', page.id === id));
		document.querySelectorAll('.nav button[data-page]').forEach(button => {
			button.classList.toggle('active', button.dataset.page === id);
			if (button.dataset.page === id) button.setAttribute('aria-current', 'page'); else button.removeAttribute('aria-current');
		});
		$('pageName').textContent = PAGES[id].title;
		document.title = PAGES[id].title + ' · WishConnect 관리자';
		$('app').classList.remove('nav-open');
		if (location.hash !== '#' + id) history.replaceState(null, '', '#' + id);
		window.scrollTo(0, 0);
		if (load && PAGES[id].load) PAGES[id].load();
	}

	function reloadCurrentList() {
		const reloaders = {intake: loadIntake, failures: loadFailures, anomaly: loadAnomalies, all: loadScholarships, always: loadAlways,
			duplicate: loadDuplicates, images: loadImages, reports: () => showReportTab(reportTab), audit: loadAudit};
		if (reloaders[currentPage]) reloaders[currentPage]();
	}

	/* ================================================================== 이벤트 연결·시작 */

	function fillSelects() {
		$('intakeStatus').innerHTML = options('parse', '', '모든 파싱 상태');
		$('anomalyStatus').innerHTML = options('recruitment', '', '모든 모집 상태');
		$('anomalyType').innerHTML = options('anomaly', '', '모든 이상 유형');
		$('scholarshipStatus').innerHTML = options('recruitment', '', '모든 모집 상태');
		$('duplicateStatus').innerHTML = options('mergeStatus', 'PENDING');
		$('duplicateOrigin').innerHTML = options('mergeOrigin', '', '모든 생성 경로');
		$('reportStatus').innerHTML = options('handle', 'PENDING', '모든 처리 상태');
		$('reportReason').innerHTML = options('reportReason', '', '모든 사유');
		$('inquiryStatus').innerHTML = options('handle', 'PENDING', '모든 처리 상태');
		$('inquiryType').innerHTML = options('inquiryType', '', '모든 유형');
		$('auditAction').innerHTML = options('action', '', '모든 작업');
	}

	function bind() {
		const click = (id, fn) => { const el = $(id); if (el) el.addEventListener('click', () => fn(el)); };
		const search = (id, key, loader) => click(id, () => { pageNo[key] = 0; loader(); });

		document.querySelectorAll('.nav button[data-page]').forEach(button => button.addEventListener('click', () => showPage(button.dataset.page)));
		document.addEventListener('click', event => {
			const go = event.target.closest('[data-go]');
			if (go) showPage(go.dataset.go);
		});
		window.addEventListener('hashchange', () => { const id = location.hash.slice(1); if (id && id !== currentPage) showPage(id); });
		$('menuToggle').onclick = () => $('app').classList.toggle('nav-open');

		// 모든 검색칸: Enter 로 검색(한글 조합 중 Enter 는 무시)
		document.addEventListener('keydown', event => {
			if (event.key !== 'Enter' || event.isComposing) return;
			const input = event.target.closest('[data-enter]');
			if (!input) return;
			event.preventDefault();
			$(input.dataset.enter).click();
		});
		$('globalSearch').addEventListener('keydown', event => {
			if (event.key !== 'Enter' || event.isComposing) return;
			$('scholarshipKeyword').value = event.currentTarget.value.trim();
			$('includeDeleted').checked = false;
			pageNo.scholarship = 0;
			showPage('all');
		});

		click('refreshDashboard', button => ui.busy(button, loadDashboard, '불러오는 중…'));
		click('refreshIntake', () => loadIntake());
		search('searchIntake', 'intake', loadIntake);
		$('intakeDate').addEventListener('change', () => { pageNo.intake = 0; loadIntake(); });
		click('refreshFailures', () => loadFailures());
		search('searchFailures', 'failures', loadFailures);
		click('retryFailures', retryFailures);
		$('failureCheckAll').addEventListener('change', event => {
			document.querySelectorAll('#failureRows [data-retry-id]:not(:disabled)').forEach(box => box.checked = event.target.checked);
			updateRetryButton();
		});
		click('refreshAnomalies', () => loadAnomalies());
		search('searchAnomalies', 'anomaly', loadAnomalies);
		search('searchScholarships', 'scholarship', loadScholarships);
		$('includeDeleted').addEventListener('change', () => { pageNo.scholarship = 0; loadScholarships(); });
		click('refreshAlways', () => loadAlways());
		click('refreshDuplicates', () => { loadDuplicates(); if (!document.querySelector('[data-dup-panel="reports"]').hidden) loadDuplicateReports(); });
		search('searchDuplicates', 'duplicate', loadDuplicates);
		$('duplicateStatus').addEventListener('change', () => { pageNo.duplicate = 0; loadDuplicates(); });
		document.querySelectorAll('[data-dup-tab]').forEach(button => button.onclick = () => showDupTab(button.dataset.dupTab));
		click('mergeSearchButton', searchMergeScholarships);
		click('createManualMerge', createManualMerge);
		click('refreshImages', () => loadImages());
		search('searchImages', 'image', loadImages);
		click('refreshReports', () => showReportTab(reportTab));
		search('searchReports', 'report', loadReports);
		search('searchInquiries', 'inquiry', loadInquiries);
		document.querySelectorAll('[data-report-tab]').forEach(button => button.onclick = () => showReportTab(button.dataset.reportTab));
		click('refreshSystem', () => loadSystem());
		click('refreshLogs', () => loadLogs());
		click('refreshJobs', () => loadJobs());
		click('refreshAudit', () => loadAudit());
		click('searchAudit', () => loadAudit());

		resetManualForm();
		$('manualAddCondition').onclick = () => $('manualConditions').appendChild(conditionRow());
		$('manualAddDocument').onclick = () => $('manualDocuments').appendChild(documentRow());
		$('manualForm').addEventListener('submit', submitManual);
		$('manualReset').onclick = () => ui.confirmAction({
			title: '입력 지우기', kind: 'primary', confirmLabel: '지우기', summary: ['수기 등록 폼에 입력한 내용을 모두 지웁니다.'],
			reversible: 'no', reversibleText: '지운 입력은 되살릴 수 없습니다.', onConfirm: async () => resetManualForm()
		});
		click('exportExcel', button => ui.busy(button, () => WC.download('/api/v1/scholarships/admin/excel',
			'scholarships-' + fmt.todayKst() + '.xlsx').catch(error => ui.toast(WC.errorText(error), 'error')), '내려받는 중…'));
		click('manualTemplate', button => ui.busy(button, () => WC.download('/api/v1/scholarships/admin/manual-excel/template',
			'scholarship-manual-template.xlsx').catch(error => ui.toast(WC.errorText(error), 'error')), '내려받는 중…'));
		excelFlow({fileId: 'manualExcelFile', dryRunId: 'manualExcelDryRun', applyId: 'manualExcelApply', resultId: 'manualExcelResult',
			path: '/api/v1/scholarships/admin/manual-excel', appliedKey: 'createdRows', applyTitle: '엑셀 신규 등록 반영',
			applySummary: ['검사를 통과한 신규 장학금을 DB 에 등록합니다.', '등록 즉시 사용자 목록에 보일 수 있습니다.']});
		excelFlow({fileId: 'bulkExcelFile', dryRunId: 'bulkExcelDryRun', applyId: 'bulkExcelApply', resultId: 'bulkExcelResult',
			path: '/api/v1/scholarships/admin/excel', appliedKey: 'appliedRows', applyTitle: '엑셀 일괄 수정 반영',
			applySummary: ['파일의 ID 와 같은 장학금을 파일 내용으로 덮어씁니다(빈 칸은 그대로).', '여러 장학금이 한 번에 바뀝니다.']});

		click('logout', button => ui.busy(button, async () => {
			try { await fetch('/api/v1/admin/auth/logout', {method: 'POST'}); } finally {
				['wc_admin_token', 'wc_admin_name', 'wc_admin_session_max'].forEach(key => sessionStorage.removeItem(key));
				location.replace('/admin/login.html');
			}
		}, '로그아웃 중…'));
	}

	async function start() {
		$('adminName').textContent = sessionStorage.getItem('wc_admin_name') || '관리자';
		WC.session.mount();
		$('intakeDate').value = fmt.todayKst();
		fillSelects();
		bind();
		$('intakeDetail').innerHTML = view.emptyHtml('왼쪽 목록에서 원문을 고르세요.', '원문·파싱 결과·연결된 장학금이 여기에 열립니다.');
		$('scholarshipDetail').innerHTML = view.emptyHtml('목록에서 장학금을 고르세요.', '상세·수정·신고가 여기에 열립니다.');
		$('duplicateReportDetail').innerHTML = view.emptyHtml('신고를 고르면 장학금 상세가 열립니다.');
		$('mergeSearchResults').innerHTML = '';
		// 시각을 KST 로 바꾸려면 서버 시계의 시간대를 먼저 알아야 한다. 늦어지면 기본값(UTC)으로 시작한다.
		await Promise.race([
			api('/api/v1/admin/system/status', {background: true}).then(data => fmt.calibrate(data.checkedAt)).catch(() => {}),
			new Promise(resolve => setTimeout(resolve, 2500))
		]);
		showPage(location.hash.slice(1) || 'dashboard');
		if (currentPage !== 'dashboard') refreshCounts().catch(() => {});
		setInterval(() => { if (!document.hidden) refreshCounts().catch(() => {}); }, 3 * 60 * 1000);
	}

	WC.console = {showPage, afterWrite, openScholarshipModal, openScholarshipPanel, refreshCounts, panels};
	start();
})();
