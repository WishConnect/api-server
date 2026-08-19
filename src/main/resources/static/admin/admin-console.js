(() => {
	'use strict';

	const TOKEN_KEY = 'wc_admin_token';
	const token = sessionStorage.getItem(TOKEN_KEY);
	if (!token) {
		location.replace('/admin/login.html');
		return;
	}

	const $ = id => document.getElementById(id);
	const escapeHtml = value => value == null ? '' : String(value).replace(/[&<>"']/g,
		char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
	const attr = escapeHtml;
	const formatDate = value => value ? String(value).replace('T', ' ').slice(0, 16) : '-';
	const inputDate = value => value ? String(value).slice(0, 16) : '';
	const safeUrl = value => /^https?:\/\//i.test(value || '') ? value : '#';
	const textOrNull = value => value && value.trim() ? value.trim() : null;
	const toast = (message, error = false) => {
		const el = $('toast'); el.textContent = message;
		el.className = 'toast show' + (error ? ' error' : '');
		setTimeout(() => el.className = 'toast', 3500);
	};

	async function api(path, options = {}) {
		const response = await fetch(path, {
			...options,
			headers: {...(options.headers || {}), Authorization: 'Bearer ' + token}
		});
		if (response.status === 401 || response.status === 403) {
			sessionStorage.removeItem(TOKEN_KEY);
			location.replace('/admin/login.html');
			throw new Error('관리자 인증이 만료됐습니다.');
		}
		const body = await response.json().catch(() => null);
		if (!response.ok || !body || body.success === false) {
			throw new Error((body && body.message) || ('요청 실패 HTTP ' + response.status));
		}
		return body.data;
	}

	async function busy(button, task, successMessage) {
		const old = button.textContent;
		button.disabled = true;
		button.textContent = '불러오는 중…';
		try {
			const value = await task();
			if (successMessage) toast(successMessage);
			return value;
		} finally {
			button.disabled = false;
			button.textContent = old;
		}
	}

	const pageState = {intake:0, failures:0, anomaly:0, scholarship:0, image:0,
		duplicate:0, report:0, inquiry:0};
	const PAGE_SIZE = 20;

	function renderPager(id, page, loader, stateKey) {
		const target = $(id);
		const totalPages = Math.max(page.totalPages || 0, 1);
		const number = page.number || 0;
		target.innerHTML = '<button class="btn" data-prev ' + (number <= 0 ? 'disabled' : '') +
			'>이전</button><span>' + (number + 1) + ' / ' + totalPages + ' · 총 ' +
			Number(page.totalElements || 0).toLocaleString() + '건</span><button class="btn" data-next ' +
			(number + 1 >= totalPages ? 'disabled' : '') + '>다음</button>';
		const prev = target.querySelector('[data-prev]');
		const next = target.querySelector('[data-next]');
		prev.onclick = () => { pageState[stateKey] = Math.max(0, number - 1); loader().catch(showError); };
		next.onclick = () => { pageState[stateKey] = number + 1; loader().catch(showError); };
	}

	function showError(error) { toast(error.message || String(error), true); }
	function query(base, values) {
		const params = new URLSearchParams();
		Object.entries(values).forEach(([key, value]) => {
			if (value !== null && value !== undefined && value !== '') params.set(key, value);
		});
		return base + '?' + params;
	}

	async function loadDashboard() {
		const [data, rows] = await Promise.all([
			api('/api/v1/scholarships/admin/overview'),
			api('/api/v1/scholarships/admin/recent?size=10')
		]);
		const values = {createdToday:data.scholarship.createdToday,total:data.scholarship.total,
			failed:data.raw.failed,open:data.scholarship.open,upcoming:data.scholarship.upcoming,
			softDeleted:data.scholarship.softDeleted};
		Object.entries(values).forEach(([key, value]) => {
			const el = document.querySelector('[data-stat="' + key + '"]'); if (el) el.textContent = value;
		});
		const body = document.querySelector('#dashboard table tbody');
		body.innerHTML = rows.length ? rows.map(row => '<tr><td><div class="thumb"></div></td><td class="title">#' +
			row.scholarshipId + ' ' + escapeHtml(row.title) + '</td><td>' + escapeHtml(row.provider || '-') +
			'</td><td><span class="badge ' + (row.softDeleted ? 'red' : 'green') + '">' +
			(row.softDeleted ? '비노출' : '저장') + '</span></td><td>' + escapeHtml(row.source || '-') +
			'</td><td>' + escapeHtml(formatDate(row.createdAt)) + '</td></tr>').join('') :
			'<tr><td colspan="6" class="muted">최근 데이터가 없습니다.</td></tr>';
		const optional = await Promise.allSettled([
			api('/api/v1/admin/jobs?page=0&size=20'),
			api('/api/v1/scholarships/merge/candidates?status=PENDING&page=0&size=1'),
			api('/api/v1/scholarships/reports?status=PENDING&page=0&size=1'),
			api('/api/v1/scholarships/admin/anomalies?page=0&size=1'),
			api('/api/v1/scholarships/admin/failures?page=0&size=1')
		]);
		const jobs = optional[0].status === 'fulfilled' ? optional[0].value : {content:[]};
		const merges = optional[1].status === 'fulfilled' ? optional[1].value : {totalCount:0};
		const reports = optional[2].status === 'fulfilled' ? optional[2].value : {totalElements:0};
		const anomalies = optional[3].status === 'fulfilled' ? optional[3].value : {totalElements:0};
		const failures = optional[4].status === 'fulfilled' ? optional[4].value : {totalElements:0};
		const failedStat = document.querySelector('[data-stat="failed"]');
		if (failedStat) failedStat.textContent = failures.totalElements || 0;
		const counts = {intake:data.raw.pending, failures:failures.totalElements || 0,
			anomaly:anomalies.totalElements || 0, always:data.scholarship.alwaysOpen,
			duplicate:merges.totalCount || 0, reports:reports.totalElements || 0};
		Object.entries(counts).forEach(([key, value]) => document
			.querySelectorAll('[data-nav-count="' + key + '"],[data-queue="' + key + '"]')
			.forEach(el => el.textContent = value));
		const warningCount = jobs.content.filter(job => ['WARNING','FAILED'].includes(job.status)).length;
		document.querySelector('[data-nav-count="system"]').textContent = warningCount;
		$('systemAlert').textContent = '알림 ' + warningCount;
		$('dashboardJobs').innerHTML = jobs.content.length ? jobs.content.slice(0,3).map(job =>
			'<div class="event"><span class="event-dot"></span><div><b>' + escapeHtml(job.jobType) +
			'</b><div class="muted">' + escapeHtml(job.errorMessage || job.summary || job.status) +
			'</div></div><small>' + escapeHtml(formatDate(job.startedAt)) + '</small></div>').join('') :
			'<div class="muted">기록된 배치가 없습니다.</div>';
	}

	const missingLabels = row => [['요약',row.hasSummary],['금액',row.hasAmount],
		['URL',row.hasHomepageUrl],['이미지',row.hasPoster]].filter(item => !item[1])
		.map(item => item[0]).join(', ') || '없음';

	function detailHtml(data) {
		const s = data.scholarship, raw = data.rawScholarships || [], conditions = data.conditions || [],
			documents = data.documents || [], images = data.images || [];
		return '<div class="detail-section"><div class="card-head"><h2>#' + s.id + ' ' +
			escapeHtml(s.title) + '</h2><span class="badge ' + (s.deletedAt ? 'red' : 'green') + '">' +
			(s.deletedAt ? '비노출' : escapeHtml(s.recruitmentStatus || '-')) + '</span></div><p>' +
			escapeHtml(s.provider || '-') + ' · ' + escapeHtml(s.primarySource || 'MANUAL') + '</p><p><b>기간</b> ' +
			escapeHtml(formatDate(s.applicationStartAt)) + ' ~ ' + escapeHtml(formatDate(s.applicationEndAt)) +
			'<br><b>금액</b> ' + escapeHtml(s.amount == null ? '-' : Number(s.amount).toLocaleString() + '원') +
			'<br><b>연락처</b> ' + escapeHtml(s.contact || '-') + '</p><p>' +
			escapeHtml(s.summary || s.description || '설명 없음') + '</p><div class="actions">' +
			(!s.deletedAt ? '<button class="btn primary" data-edit-scholarship="' + s.id + '">통합 수정</button>' : '') +
			(safeUrl(s.detailUrl || s.homepageUrl) !== '#' ? '<a class="btn" target="_blank" rel="noopener noreferrer" href="' +
			attr(safeUrl(s.detailUrl || s.homepageUrl)) + '">원문 열기</a>' : '') + '</div></div>' +
			'<div class="detail-section"><h3>이미지 ' + images.length + '개</h3>' +
			(images.map(image => '<div class="image-card"><img src="' + attr(safeUrl(image.previewUrl)) +
			'" alt=""><small>' + escapeHtml(image.originalName || image.imageType || '이미지') +
			'<br>출처 ' + escapeHtml(image.sourceUrl || '-') + '</small></div>').join('') || '<p class="muted">없음</p>') + '</div>' +
			'<div class="detail-section"><h3>조건 ' + conditions.length + '개</h3>' +
			conditions.map(c => '<div class="field"><div class="box"><b>' + escapeHtml(c.conditionType) +
			'</b> · ' + escapeHtml(c.necessity) + ' · ' + escapeHtml(c.operator) + '<br>' +
			escapeHtml(c.valueString || [c.valueInt,c.valueIntMax].filter(v => v != null).join(' ~ ') || '-') +
			'</div></div>').join('') + '</div><div class="detail-section"><h3>제출서류 ' + documents.length +
			'개</h3>' + documents.map(d => '<p>' + escapeHtml(d.name) + (d.essay ? ' · 자기소개서' : '') +
			(safeUrl(d.downloadUrl) !== '#' ? ' · <a target="_blank" href="' + attr(safeUrl(d.downloadUrl)) + '">다운로드</a>' : '') +
			'</p>').join('') + '</div><div class="detail-section"><h3>원문 ' + raw.length + '개</h3>' +
			raw.map(r => '<details><summary>#' + r.id + ' ' + escapeHtml(r.source) + ' · ' + escapeHtml(r.parseStatus) +
			'</summary><div class="json">' + escapeHtml(r.rawHtml || JSON.stringify(r.rawJson, null, 2) || '원문 없음') +
			'</div></details>').join('') + '</div>';
	}

	function bindDetailActions(target) {
		target.querySelectorAll('[data-edit-scholarship]').forEach(button =>
			button.onclick = () => openEditScholarship(Number(button.dataset.editScholarship)).catch(showError));
	}

	async function loadScholarshipDetail(id, targetId = 'scholarshipDetail') {
		const target = $(targetId); target.innerHTML = '<div class="empty">상세 정보를 불러오는 중입니다.</div>';
		const data = await api('/api/v1/scholarships/admin/scholarships/' + id);
		target.innerHTML = detailHtml(data); bindDetailActions(target); return data;
	}

	async function loadScholarships() {
		const path = query('/api/v1/scholarships/admin/scholarships', {
			page:pageState.scholarship,size:PAGE_SIZE,sort:'createdAt,desc',
			keyword:$('scholarshipKeyword').value.trim(),source:$('scholarshipSource').value.trim(),
			status:$('scholarshipStatus').value,includeDeleted:$('includeDeleted').checked
		});
		const page = await api(path), body = $('scholarshipRows');
		body.innerHTML = page.content.length ? page.content.map(row => '<tr data-scholarship-id="' +
			row.scholarshipId + '" style="cursor:pointer"><td>' + row.scholarshipId + '</td><td class="title">' +
			escapeHtml(row.title) + '<br><small class="muted">' + escapeHtml(row.provider || '-') + '</small></td><td>' +
			escapeHtml(row.recruitmentStatus || '-') + '</td><td>' + escapeHtml(row.source || 'MANUAL') +
			'</td><td class="' + (missingLabels(row) === '없음' ? 'green' : 'red') + '">' +
			escapeHtml(missingLabels(row)) + '</td></tr>').join('') :
			'<tr><td colspan="5" class="muted">검색 결과가 없습니다.</td></tr>';
		body.querySelectorAll('[data-scholarship-id]').forEach(row =>
			row.onclick = () => loadScholarshipDetail(row.dataset.scholarshipId).catch(showError));
		renderPager('scholarshipPager', page, loadScholarships, 'scholarship');
	}

	async function loadIntake() {
		const page = await api(query('/api/v1/scholarships/admin/intake', {
			date:$('intakeDate').value,keyword:$('intakeKeyword').value.trim(),source:$('intakeSource').value.trim(),
			status:$('intakeStatus').value,page:pageState.intake,size:PAGE_SIZE,sort:'crawledAt,desc'
		}));
		const body = $('intakeRows');
		body.innerHTML = page.content.length ? page.content.map(row => '<tr data-raw-id="' + row.rawId +
			'" style="cursor:pointer"><td>#' + row.rawId + '</td><td class="title">' + escapeHtml(row.title || '-') +
			(row.scholarshipId ? '<br><small class="muted">scholarship #' + row.scholarshipId + '</small>' : '') +
			'</td><td>' + escapeHtml(row.source) + '</td><td><span class="badge ' +
			(row.parseStatus === 'PARSED' ? 'green' : row.parseStatus === 'FAILED' ? 'red' : 'yellow') + '">' +
			escapeHtml(row.parseStatus) + '</span></td><td>' + escapeHtml(formatDate(row.crawledAt)) + '</td></tr>').join('') :
			'<tr><td colspan="5" class="muted">선택한 날짜의 수집 데이터가 없습니다.</td></tr>';
		body.querySelectorAll('[data-raw-id]').forEach(row => row.onclick = () =>
			loadRawDetail(row.dataset.rawId, 'intakeDetail').catch(showError));
		renderPager('intakePager', page, loadIntake, 'intake');
		if (page.content.length) await loadRawDetail(page.content[0].rawId, 'intakeDetail');
	}

	async function loadRawDetail(rawId, targetId) {
		const data = await api('/api/v1/scholarships/admin/raw/' + rawId), target = $(targetId);
		if (data.scholarship) {
			target.innerHTML = detailHtml(data.scholarship) + rawHeader(data);
			bindDetailActions(target);
		} else {
			target.innerHTML = rawHeader(data) + '<div class="actions"><button class="btn primary" data-manual-raw="' +
				data.rawId + '">수기 정제</button></div>';
		}
		target.querySelectorAll('[data-manual-raw]').forEach(button =>
			button.onclick = () => openEditRaw(Number(button.dataset.manualRaw)).catch(showError));
		return data;
	}

	function rawHeader(data) {
		return '<div class="detail-section"><h3>원본 #' + data.rawId + ' · ' + escapeHtml(data.source) +
			'</h3><p><span class="badge ' + (data.parseStatus === 'FAILED' ? 'red' : 'yellow') + '">' +
			escapeHtml(data.parseStatus) + '</span> ' + escapeHtml(data.parseError || '') + '</p><div class="json">' +
			escapeHtml(data.rawHtml || JSON.stringify(data.rawJson, null, 2) || '원문 없음') + '</div></div>';
	}

	async function loadFailures() {
		const page = await api(query('/api/v1/scholarships/admin/failures', {
			keyword:$('failureKeyword').value.trim(),source:$('failureSource').value.trim(),
			status:$('failureStatus').value,retryableOnly:$('failureRetryable').checked,
			page:pageState.failures,size:PAGE_SIZE,sort:'updatedAt,desc'
		}));
		const body = $('failureRows');
		body.innerHTML = page.content.length ? page.content.map(row => {
			const retryable = String(row.source || '').startsWith('UNIV_');
			return '<tr><td><input type="checkbox" data-raw-id="' + row.rawId + '" ' + (retryable ? '' : 'disabled') +
				'></td><td><span class="badge ' + (row.status === 'FAILED' ? 'red' : 'yellow') + '">' +
				escapeHtml(row.status) + '</span></td><td class="title">raw #' + row.rawId +
				(row.scholarshipId ? '<br><small>scholarship #' + row.scholarshipId + '</small>' : '') + '</td><td>' +
				escapeHtml(row.source || '-') + '</td><td>' + escapeHtml(row.error || '-') + '</td><td>' +
				escapeHtml(formatDate(row.updatedAt)) + '</td><td><button class="btn" data-failure-edit="' + row.rawId +
				'">원문·수기 수정</button></td></tr>';
		}).join('') : '<tr><td colspan="7" class="muted">재처리 대상이 없습니다.</td></tr>';
		body.querySelectorAll('[data-failure-edit]').forEach(button =>
			button.onclick = () => openEditRaw(Number(button.dataset.failureEdit)).catch(showError));
		renderPager('failurePager', page, loadFailures, 'failures');
	}

	async function retryFailures() {
		const ids = [...document.querySelectorAll('#failureRows [data-raw-id]:checked')].map(input => input.dataset.rawId);
		if (!ids.length) throw new Error('재처리할 대학 원본을 선택하세요.');
		if (!confirm(ids.length + '건을 LLM으로 다시 파싱할까요? 비용이 발생합니다.')) return;
		const result = await api(query('/api/v1/scholarships/parse/univ-llm', {
			limit:Math.min(ids.length,100),reparse:true,dryRun:false,rawIds:ids.join(','),skipComplete:false
		}), {method:'POST'});
		toast('재처리 완료 · 성공 ' + result.parsedCount + ' · 건너뜀 ' + result.skippedCount + ' · 실패 ' + result.failedCount);
		await loadFailures();
	}

	const anomalyNames = {EMPTY_TITLE:'제목 없음',MISSING_PROVIDER:'기관 없음',DATE_REVERSED:'기간 역전',
		OPEN_BUT_ENDED:'마감일 경과 OPEN',MISSING_LINK:'링크 없음',MISSING_CONDITION:'조건 없음'};
	async function loadAnomalies() {
		const page = await api(query('/api/v1/scholarships/admin/anomalies', {
			keyword:$('anomalyKeyword').value.trim(),source:$('anomalySource').value.trim(),
			status:$('anomalyStatus').value,anomalyType:$('anomalyType').value,
			page:pageState.anomaly,size:PAGE_SIZE,sort:'createdAt,desc'
		}));
		const body = $('anomalyRows');
		body.innerHTML = page.content.length ? page.content.map(row => '<tr><td>' + row.scholarshipId +
			'</td><td class="title">' + escapeHtml(row.title || '(제목 없음)') + '<br><small class="muted">' +
			escapeHtml(row.provider || '기관 없음') + '</small></td><td>' + escapeHtml(row.recruitmentStatus || '-') +
			'</td><td>' + escapeHtml(formatDate(row.applicationStartAt)) + ' ~ ' + escapeHtml(formatDate(row.applicationEndAt)) +
			'</td><td>' + row.anomalyTypes.map(type => '<span class="badge red" title="' + attr(type) + '">' +
			escapeHtml(anomalyNames[type] || type) + '</span>').join(' ') + '</td><td><button class="btn" data-anomaly-id="' +
			row.scholarshipId + '" data-anomaly-title="' + attr(row.title || '') + '">상세·수정</button></td></tr>').join('') :
			'<tr><td colspan="6" class="muted">현재 탐지된 이상이 없습니다.</td></tr>';
		body.querySelectorAll('[data-anomaly-id]').forEach(button => button.onclick = async () => {
			$('scholarshipKeyword').value = button.dataset.anomalyTitle;
			pageState.scholarship = 0; showPage('all');
			await loadScholarships(); await loadScholarshipDetail(button.dataset.anomalyId);
		});
		renderPager('anomalyPager', page, loadAnomalies, 'anomaly');
	}

	async function loadImages() {
		const page = await api(query('/api/v1/scholarships/admin/images', {
			keyword:$('imageKeyword').value.trim(),source:$('imageSource').value.trim(),
			hasImage:$('imagePresence').value,page:pageState.image,size:PAGE_SIZE,sort:'createdAt,desc'
		}));
		$('imageGrid').innerHTML = page.content.length ? page.content.map(row => '<article class="image-card">' +
			(row.previewUrl ? '<img src="' + attr(safeUrl(row.previewUrl)) + '" alt="">' : '<div class="image-empty">이미지 없음</div>') +
			'<b>#' + row.scholarshipId + ' ' + escapeHtml(row.scholarshipTitle) + '</b><p class="muted">' +
			escapeHtml(row.provider || '-') + ' · ' + escapeHtml(row.source || 'MANUAL') + '</p><p class="muted">image #' +
			escapeHtml(row.imageId || '-') + '<br>원본 ' + escapeHtml(row.sourceUrl || '-') + '</p><button class="btn primary" data-image-edit="' +
			row.scholarshipId + '" data-image-title="' + attr(row.scholarshipTitle) + '">' +
			(row.imageId ? '이미지 교체' : '이미지 등록') + '</button></article>').join('') :
			'<div class="placeholder">조건에 맞는 장학금이 없습니다.</div>';
		$('imageGrid').querySelectorAll('[data-image-edit]').forEach(button => button.onclick = () => {
			currentImageScholarshipId = Number(button.dataset.imageEdit);
			$('imageDialogTitle').textContent = '#' + currentImageScholarshipId + ' ' + button.dataset.imageTitle;
			$('imageEditUrl').value = ''; $('imageEditFile').value = ''; $('imageDialog').showModal();
		});
		renderPager('imagePager', page, loadImages, 'image');
	}

	let currentImageScholarshipId = null;
	async function saveImage() {
		if (!currentImageScholarshipId) return;
		const file = $('imageEditFile').files[0], url = $('imageEditUrl').value.trim();
		if (!file && !url) throw new Error('이미지 URL 또는 파일을 선택하세요.');
		if (file) {
			const form = new FormData(); form.append('file', file);
			await api('/api/v1/scholarships/admin/scholarships/' + currentImageScholarshipId + '/image-file',
				{method:'PUT',body:form});
		} else {
			await api('/api/v1/scholarships/admin/scholarships/' + currentImageScholarshipId + '/image-url',
				{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({imageUrl:url})});
		}
		$('imageDialog').close(); toast('이미지를 저장했습니다.'); await loadImages();
	}

	function sideHtml(side, label) {
		return '<article class="candidate"><span class="badge blue">' + label + '</span><h2>' +
			escapeHtml(side.title) + '</h2><p class="muted">#' + side.scholarshipId + ' · ' +
			escapeHtml(side.provider || '-') + '</p><p><b>기간</b> ' + escapeHtml(side.applicationPeriod || '-') +
			'</p><p><b>금액</b> ' + escapeHtml(side.amount == null ? '-' : Number(side.amount).toLocaleString() + '원') +
			'</p><p><b>출처</b> ' + escapeHtml(side.source || '-') + '</p></article>';
	}

	async function loadDuplicates() {
		const [result, reports] = await Promise.all([
			api(query('/api/v1/scholarships/merge/candidates', {status:'PENDING',origin:$('duplicateOrigin').value,
				keyword:$('duplicateKeyword').value.trim(),page:pageState.duplicate,size:10})),
			api('/api/v1/scholarships/reports?status=PENDING&reason=DUPLICATE&page=0&size=20&sort=createdAt,desc')
		]);
		$('duplicateReports').innerHTML = reports.content.length ? reports.content.map(row => '<div class="queue-item"><span>#' +
			row.reportId + ' · 장학금 #' + row.scholarshipId + ' ' + escapeHtml(row.scholarshipTitle) + ' · ' +
			escapeHtml(row.scholarshipProvider || '기관 없음') + '</span><span class="actions">' +
			'<button class="btn" data-report-primary="' + row.scholarshipId + '">유지 쪽</button><button class="btn" data-report-duplicate="' +
			row.scholarshipId + '">내릴 쪽</button></span></div>').join('') : '대기 중인 중복 신고가 없습니다.';
		$('duplicateReports').querySelectorAll('[data-report-primary]').forEach(button => button.onclick = () =>
			$('mergePrimaryId').value = button.dataset.reportPrimary);
		$('duplicateReports').querySelectorAll('[data-report-duplicate]').forEach(button => button.onclick = () =>
			$('mergeDuplicateId').value = button.dataset.reportDuplicate);
		const target = $('duplicateContent'); target.className = '';
		target.innerHTML = result.items.length ? result.items.map(item => '<div class="card"><div class="compare">' +
			sideHtml(item.primary,'유지 대상') + '<div class="merge"><div><span class="badge ' +
			(item.origin === 'MANUAL' ? 'blue' : 'yellow') + '">' + escapeHtml(item.origin) + '</span><p>' +
			escapeHtml(item.reason || '중복 후보') + '</p></div></div>' + sideHtml(item.duplicate,'병합 대상') +
			'</div><div class="toolbar" style="margin-top:14px"><button class="btn primary" data-merge-approve="' +
			item.candidateId + '">확인 후 병합</button><button class="btn" data-merge-reject="' + item.candidateId +
			'">중복 아님</button></div></div>').join('') : '<div class="placeholder">승인 대기 후보가 없습니다.</div>';
		target.querySelectorAll('[data-merge-approve]').forEach(button => button.onclick = async () => {
			if (!confirm('병합 대상은 소프트 삭제되고 참조 데이터가 유지 대상으로 이동합니다. 실행할까요?')) return;
			await api('/api/v1/scholarships/merge/candidates/' + button.dataset.mergeApprove + '/approve',{method:'POST'});
			toast('중복 장학금을 병합했습니다.'); await loadDuplicates();
		});
		target.querySelectorAll('[data-merge-reject]').forEach(button => button.onclick = async () => {
			const note = prompt('중복이 아닌 근거를 입력하세요.','별도 학기·회차 또는 대상이 다른 공고');
			if (note === null) return;
			await api('/api/v1/scholarships/merge/candidates/' + button.dataset.mergeReject + '/reject?note=' +
				encodeURIComponent(note),{method:'POST'}); await loadDuplicates();
		});
		renderPager('duplicatePager', {number:pageState.duplicate,totalElements:result.totalCount,
			totalPages:Math.ceil(result.totalCount/10)}, loadDuplicates, 'duplicate');
	}

	async function createManualMerge() {
		const primary = Number($('mergePrimaryId').value), duplicate = Number($('mergeDuplicateId').value);
		if (!primary || !duplicate) throw new Error('유지할 장학금과 내릴 장학금 ID를 모두 입력하세요.');
		await api('/api/v1/scholarships/merge/candidates/manual', {method:'POST',headers:{'Content-Type':'application/json'},
			body:JSON.stringify({primaryScholarshipId:primary,duplicateScholarshipId:duplicate,
				reason:textOrNull($('mergeReason').value)})});
		toast('수기 중복 후보를 승인 대기 큐에 추가했습니다.'); await loadDuplicates();
	}

	async function searchMergeScholarships() {
		const keyword = $('mergeScholarshipSearch').value.trim();
		if (!keyword) throw new Error('찾을 장학금명 또는 기관을 입력하세요.');
		const page = await api(query('/api/v1/scholarships/admin/scholarships', {
			keyword,page:0,size:20,includeDeleted:false,sort:'createdAt,desc'
		}));
		$('mergeScholarshipResults').innerHTML = page.content.length ? page.content.map(row =>
			'<div class="queue-item"><span>#' + row.scholarshipId + ' · <b>' + escapeHtml(row.title) +
			'</b> · ' + escapeHtml(row.provider || '기관 없음') + '</span><span class="actions">' +
			'<button class="btn" data-merge-primary="' + row.scholarshipId + '">유지 쪽</button>' +
			'<button class="btn" data-merge-duplicate="' + row.scholarshipId + '">내릴 쪽</button></span></div>'
		).join('') : '검색 결과가 없습니다.';
		$('mergeScholarshipResults').querySelectorAll('[data-merge-primary]').forEach(button =>
			button.onclick = () => $('mergePrimaryId').value = button.dataset.mergePrimary);
		$('mergeScholarshipResults').querySelectorAll('[data-merge-duplicate]').forEach(button =>
			button.onclick = () => $('mergeDuplicateId').value = button.dataset.mergeDuplicate);
	}

	let resolutionTarget = null;
	function openResolution(kind, id, status = 'RESOLVED') {
		resolutionTarget = {kind,id}; $('resolveStatus').value = status; $('resolveNote').value = '';
		$('resolveTitle').textContent = kind === 'report' ? '장학금 신고 처리' : '콘텐츠 이용문의 처리';
		$('resolveHelp').textContent = kind === 'report'
			? '이 답변은 사용자의 내 신고 목록에 표시됩니다. 실제 수정·병합 후 처리 완료하세요.'
			: '기관·권리자에게 회신할 수 있도록 실제 조치 결과를 구체적으로 남기세요.';
		$('resolveDialog').showModal();
	}

	async function saveResolution() {
		const note = $('resolveNote').value.trim(), status = $('resolveStatus').value;
		if (!note) throw new Error('처리 결과 또는 반려 근거를 입력하세요.');
		const path = resolutionTarget.kind === 'report'
			? '/api/v1/scholarships/reports/' + resolutionTarget.id
			: '/api/v1/admin/content-inquiries/' + resolutionTarget.id;
		await api(path,{method:'PATCH',headers:{'Content-Type':'application/json'},
			body:JSON.stringify({status,adminNote:note})});
		$('resolveDialog').close(); toast('처리 결과를 저장했습니다.');
		await (resolutionTarget.kind === 'report' ? loadReports() : loadInquiries());
	}

	async function loadReports() {
		const page = await api(query('/api/v1/scholarships/reports', {status:$('reportStatus').value,
			reason:$('reportReason').value,keyword:$('reportKeyword').value.trim(),page:pageState.report,
			size:PAGE_SIZE,sort:'createdAt,desc'}));
		const body = $('reportRows');
		body.innerHTML = page.content.length ? page.content.map(row => '<tr><td>#' + row.reportId +
			'</td><td class="title">#' + row.scholarshipId + ' ' + escapeHtml(row.scholarshipTitle) +
			'<br><small class="muted">' + escapeHtml(row.scholarshipProvider || '기관 없음') + '</small></td><td>' +
			escapeHtml((row.reasons || []).join(', ')) + '</td><td>' + escapeHtml(row.detail || '-') + '</td><td>' +
			escapeHtml(formatDate(row.createdAt)) + '</td><td>' + (row.status === 'PENDING' ?
			'<div class="actions"><button class="btn primary" data-resolve-report="' + row.reportId + '">해결</button>' +
			'<button class="btn" data-reject-report="' + row.reportId + '">반려</button>' +
			'<button class="btn" data-report-edit="' + row.scholarshipId + '">장학금 수정</button></div>' :
			'<span class="badge blue">' + escapeHtml(row.status) + '</span><br><small>' + escapeHtml(row.adminNote || '-') + '</small>') +
			'</td></tr>').join('') : '<tr><td colspan="6" class="muted">신고가 없습니다.</td></tr>';
		body.querySelectorAll('[data-resolve-report]').forEach(b => b.onclick = () => openResolution('report', b.dataset.resolveReport));
		body.querySelectorAll('[data-reject-report]').forEach(b => b.onclick = () => openResolution('report', b.dataset.rejectReport, 'REJECTED'));
		body.querySelectorAll('[data-report-edit]').forEach(b => b.onclick = () => openEditScholarship(Number(b.dataset.reportEdit)).catch(showError));
		renderPager('reportPager', page, loadReports, 'report');
	}

	async function loadInquiries() {
		const page = await api(query('/api/v1/admin/content-inquiries', {status:$('inquiryStatus').value,
			type:$('inquiryType').value,keyword:$('inquiryKeyword').value.trim(),page:pageState.inquiry,
			size:PAGE_SIZE,sort:'createdAt,desc'}));
		const body = $('inquiryRows');
		body.innerHTML = page.content.length ? page.content.map(row => '<tr><td>#' + row.inquiryId + '</td><td><b>' +
			escapeHtml(row.inquiryType || 'OTHER') + '</b><br>' + escapeHtml(row.inquiryTarget || '-') + '</td><td>' +
			escapeHtml(row.organizationName || '-') + '<br><small>' + escapeHtml(row.email) + '<br>' +
			escapeHtml(row.phone || '') + '</small></td><td>' + escapeHtml(row.content) +
			(row.attachmentUrl ? '<br><a class="btn" target="_blank" href="' + attr(safeUrl(row.attachmentUrl)) + '">' +
			escapeHtml(row.attachmentName || '첨부파일') + '</a>' : '') + '</td><td>' + escapeHtml(formatDate(row.createdAt)) +
			'</td><td>' + (row.status === 'PENDING' ? '<div class="actions"><button class="btn primary" data-resolve-inquiry="' +
			row.inquiryId + '">해결</button><button class="btn" data-reject-inquiry="' + row.inquiryId + '">반려</button></div>' :
			'<span class="badge blue">' + escapeHtml(row.status) + '</span><br><small>' + escapeHtml(row.adminNote || '-') +
			'</small>') + '</td></tr>').join('') : '<tr><td colspan="6" class="muted">문의가 없습니다.</td></tr>';
		body.querySelectorAll('[data-resolve-inquiry]').forEach(b => b.onclick = () => openResolution('inquiry', b.dataset.resolveInquiry));
		body.querySelectorAll('[data-reject-inquiry]').forEach(b => b.onclick = () => openResolution('inquiry', b.dataset.rejectInquiry, 'REJECTED'));
		renderPager('inquiryPager', page, loadInquiries, 'inquiry');
	}

	let editMode = null;
	const option = (value, current, label = value) => '<option value="' + value + '" ' +
		(value === (current || '') ? 'selected' : '') + '>' + label + '</option>';
	const selectOptions = (values, current, blank = '미정') => '<option value="">' + blank + '</option>' +
		values.map(value => option(value,current)).join('');

	async function openEditScholarship(id) {
		const detail = await api('/api/v1/scholarships/admin/scholarships/' + id);
		editMode = {scholarshipId:id,rawId:null,raw:null}; renderEdit(detail, null); $('editDialog').showModal();
	}

	async function openEditRaw(rawId) {
		const raw = await api('/api/v1/scholarships/admin/raw/' + rawId);
		editMode = {scholarshipId:raw.scholarshipId,rawId,raw};
		renderEdit(raw.scholarship, raw); $('editDialog').showModal();
	}

	function renderEdit(detail, raw) {
		const s = detail ? detail.scholarship : {
			title:raw.sourceId || '',provider:'',scholarshipType:'EXTERNAL',homepageUrl:raw.sourceUrl,
			detailUrl:raw.sourceUrl,recruitmentStatus:'',combined:false
		};
		$('editTitle').textContent = detail ? '#' + s.id + ' 장학금 통합 수정' : 'raw #' + raw.rawId + ' 수기 정제';
		$('editFields').innerHTML = (raw ? '<div class="notice">원본은 읽기 전용입니다.</div><details><summary>수집 원문 보기</summary><div class="json">' +
			escapeHtml(raw.rawHtml || JSON.stringify(raw.rawJson,null,2) || '원문 없음') + '</div></details>' : '') +
			'<div class="form-grid" style="margin-top:14px">' +
			field('장학금명 *','title',s.title,true) + field('기관','provider',s.provider) +
			selectField('유형','scholarshipType',['INTERNAL','EXTERNAL','WORK_STUDY'],s.scholarshipType) +
			selectField('모집상태','recruitmentStatus',['UPCOMING','OPEN','ALWAYS_OPEN','CLOSED'],s.recruitmentStatus,true) +
			field('모집 시작','applicationStartAt',inputDate(s.applicationStartAt),false,'datetime-local') +
			field('모집 종료','applicationEndAt',inputDate(s.applicationEndAt),false,'datetime-local') +
			field('지원금액','amount',s.amount,false,'number') + field('선발인원','selectionCount',s.selectionCount,false,'number') +
			field('문의처','contact',s.contact) + field('요약','summary',s.summary,false,'text','wide') +
			areaField('상세설명','description',s.description) + field('홈페이지 URL','homepageUrl',s.homepageUrl,false,'url') +
			field('상세 URL','detailUrl',s.detailUrl,false,'url') + field('새 이미지 URL(선택)','imageSourceUrl','',false,'url') +
			selectField('공지 종류','noticeKind',['RECRUITMENT','RESULT','GUIDE','NOT_SCHOLARSHIP'],s.noticeKind,true) +
			selectField('제출 채널','submissionChannel',['ONLINE','EMAIL','POST','VISIT','FAX','MIXED','THIRD_PARTY'],s.submissionChannel,true) +
			field('제출 방법','submissionMethod',s.submissionMethod) + areaField('제출 근거','submissionEvidence',s.submissionEvidence) +
			selectField('자기소개서',['essayRequirement'],['REQUIRED','CONDITIONAL','NOT_REQUIRED'],s.essayRequirement,true) +
			field('자소서 근거','essayEvidence',s.essayEvidence) +
			selectField('면접','interviewRequirement',['REQUIRED','CONDITIONAL','NOT_REQUIRED'],s.interviewRequirement,true) +
			field('면접 근거','interviewEvidence',s.interviewEvidence) +
			'<label><input name="combined" type="checkbox" style="min-width:auto" ' + (s.combined ? 'checked' : '') + '> 통합 공고</label></div>' +
			'<div class="sub-form"><div class="card-head"><h2>지원 조건</h2><button type="button" class="btn" id="editAddCondition">조건 추가</button></div><div id="editConditions" class="edit-list"></div></div>' +
			'<div class="sub-form"><div class="card-head"><h2>제출서류</h2><button type="button" class="btn" id="editAddDocument">서류 추가</button></div><div id="editDocuments" class="edit-list"></div></div>';
		$('editAddCondition').onclick = () => addEditCondition(); $('editAddDocument').onclick = () => addEditDocument();
		(detail ? detail.conditions : []).forEach(addEditCondition); (detail ? detail.documents : []).forEach(addEditDocument);
	}

	function field(label,name,value,required=false,type='text',wide='') {
		return '<div class="form-field ' + wide + '"><label>' + label + '</label><input name="' + name + '" type="' + type +
			'" value="' + attr(value == null ? '' : value) + '" ' + (required ? 'required' : '') + '></div>';
	}
	function areaField(label,name,value) { return '<div class="form-field wide"><label>' + label +
		'</label><textarea name="' + name + '">' + escapeHtml(value || '') + '</textarea></div>'; }
	function selectField(label,name,values,current,blank=false) {
		if (Array.isArray(name)) name = name[0];
		return '<div class="form-field"><label>' + label + '</label><select name="' + name + '">' +
			selectOptions(values,current,blank ? '미정' : values[0]) + '</select></div>';
	}

	function addEditCondition(value = {}) {
		const row = document.createElement('div'); row.className = 'edit-row';
		const refs = value.refs || [];
		row.innerHTML = '<div class="toolbar"><select data-field="conditionType">' +
			selectOptions(['UNIVERSITY_TYPE','MAJOR_FIELD','GRADE_LEVEL','ACADEMIC_CRITERIA','INCOME_CRITERIA',
				'REGION_RESIDENCY','SPECIFIC_QUALIFICATION','RESTRICTION','FINANCIAL_AID_TYPE','RECOMMENDATION_REQUIRED'],value.conditionType,'유형') +
			'</select><select data-field="necessity">' + selectOptions(['REQUIRED','PREFERRED'],value.necessity,'필수/우대') +
			'</select><select data-field="operator">' + selectOptions(['EQ','IN','GTE','LTE','BETWEEN'],value.operator,'연산자') +
			'</select><input data-field="valueString" placeholder="조건 원문 *" value="' + attr(value.valueString || '') +
			'"><input data-field="valueInt" type="number" placeholder="숫자값" value="' + attr(value.valueInt == null ? '' : value.valueInt) +
			'"><input data-field="valueIntMax" type="number" placeholder="최대값" value="' + attr(value.valueIntMax == null ? '' : value.valueIntMax) +
			'"><input data-field="refLabels" placeholder="새 참조 라벨(쉼표)" value=""><input data-field="refIds" type="hidden" value="' +
			attr(refs.filter(r=>r.refId!=null).map(r=>r.refId).join(',')) + '"><input data-field="refCodes" type="hidden" value="' +
			attr(refs.filter(r=>r.refCode).map(r=>r.refCode).join(',')) + '"><button type="button" class="btn danger">삭제</button></div>';
		row.querySelector('button').onclick = () => row.remove(); $('editConditions').appendChild(row);
	}

	function addEditDocument(value = {}) {
		const row = document.createElement('div'); row.className = 'edit-row';
		row.innerHTML = '<div class="toolbar"><input data-field="name" placeholder="서류명 *" value="' + attr(value.name || '') +
			'"><label><input data-field="essay" type="checkbox" style="min-width:auto" ' + (value.essay ? 'checked' : '') +
			'> 자기소개서</label><input data-field="downloadUrl" type="url" placeholder="다운로드 URL" value="' +
			attr(value.downloadUrl || '') + '"><button type="button" class="btn danger">삭제</button></div>';
		row.querySelector('button').onclick = () => row.remove(); $('editDocuments').appendChild(row);
	}

	function editPayload(form) {
		const value = name => form.elements[name] ? form.elements[name].value : '';
		const number = name => value(name) === '' ? null : Number(value(name));
		const list = text => text.split(',').map(v => v.trim()).filter(Boolean);
		const conditions = [...$('editConditions').children].map(row => ({
			conditionType:row.querySelector('[data-field="conditionType"]').value,
			necessity:row.querySelector('[data-field="necessity"]').value,
			operator:row.querySelector('[data-field="operator"]').value,
			valueString:row.querySelector('[data-field="valueString"]').value.trim(),
			valueInt:row.querySelector('[data-field="valueInt"]').value === '' ? null : Number(row.querySelector('[data-field="valueInt"]').value),
			valueIntMax:row.querySelector('[data-field="valueIntMax"]').value === '' ? null : Number(row.querySelector('[data-field="valueIntMax"]').value),
			refLabels:list(row.querySelector('[data-field="refLabels"]').value),
			refIds:list(row.querySelector('[data-field="refIds"]').value).map(Number),
			refCodes:list(row.querySelector('[data-field="refCodes"]').value)
		}));
		if (conditions.some(c => !c.valueString)) throw new Error('모든 조건에 원문을 입력하세요.');
		const documents = [...$('editDocuments').children].map((row,index) => ({
			name:row.querySelector('[data-field="name"]').value.trim(),essay:row.querySelector('[data-field="essay"]').checked,
			displayOrder:index,downloadUrl:textOrNull(row.querySelector('[data-field="downloadUrl"]').value)
		}));
		if (documents.some(d => !d.name)) throw new Error('모든 제출서류에 이름을 입력하세요.');
		return {title:value('title').trim(),provider:textOrNull(value('provider')),summary:textOrNull(value('summary')),
			description:textOrNull(value('description')),scholarshipType:value('scholarshipType'),
			applicationStartAt:textOrNull(value('applicationStartAt')),applicationEndAt:textOrNull(value('applicationEndAt')),
			recruitmentStatus:textOrNull(value('recruitmentStatus')),selectionCount:number('selectionCount'),amount:number('amount'),
			homepageUrl:textOrNull(value('homepageUrl')),detailUrl:textOrNull(value('detailUrl')),
			noticeKind:textOrNull(value('noticeKind')),combined:form.elements.combined.checked,
			submissionMethod:textOrNull(value('submissionMethod')),submissionChannel:textOrNull(value('submissionChannel')),
			submissionEvidence:textOrNull(value('submissionEvidence')),contact:textOrNull(value('contact')),
			essayRequirement:textOrNull(value('essayRequirement')),essayEvidence:textOrNull(value('essayEvidence')),
			interviewRequirement:textOrNull(value('interviewRequirement')),interviewEvidence:textOrNull(value('interviewEvidence')),
			source:editMode.raw ? {sourceUrl:editMode.raw.sourceUrl,rawHtml:editMode.raw.rawHtml} : null,
			conditions,documents,imageSourceUrl:textOrNull(value('imageSourceUrl'))};
	}

	async function submitEdit(event) {
		event.preventDefault(); const payload = editPayload(event.currentTarget);
		const path = editMode.scholarshipId
			? '/api/v1/scholarships/manual/' + editMode.scholarshipId + '/full'
			: '/api/v1/scholarships/admin/raw/' + editMode.rawId + '/manual';
		await api(path,{method:editMode.scholarshipId?'PUT':'POST',headers:{'Content-Type':'application/json'},
			body:JSON.stringify(payload)});
		$('editDialog').close(); toast(editMode.scholarshipId ? '장학금을 통합 수정했습니다.' : '원본을 수기로 정제했습니다.');
		if (document.querySelector('#failures.page.active')) await loadFailures();
		if (document.querySelector('#intake.page.active')) await loadIntake();
		if (document.querySelector('#all.page.active')) await loadScholarships();
	}

	async function loadAlways() {
		const page = await api('/api/v1/scholarships/admin/always-open?page=0&size=100&sort=createdAt,asc');
		const body = $('alwaysRows'); body.innerHTML = page.content.length ? page.content.map(row => '<tr><td class="title">#' +
			row.id + ' ' + escapeHtml(row.title) + '</td><td>' + escapeHtml(row.provider || '-') + '</td><td>' +
			escapeHtml(formatDate(row.createdAt)) + '</td><td>' + escapeHtml(formatDate(row.reviewedAt)) + '</td><td>' +
			row.conditionCount + '</td><td><button class="btn primary" data-always-confirm="' + row.id + '">계속 모집</button> ' +
			'<button class="btn" data-always-edit="' + row.id + '">수정</button></td></tr>').join('') :
			'<tr><td colspan="6" class="muted">상시모집 장학금이 없습니다.</td></tr>';
		body.querySelectorAll('[data-always-confirm]').forEach(b => b.onclick = async () => {
			await api('/api/v1/scholarships/admin/always-open/' + b.dataset.alwaysConfirm + '/confirm',{method:'PATCH'}); await loadAlways();
		});
		body.querySelectorAll('[data-always-edit]').forEach(b => b.onclick = () => openEditScholarship(Number(b.dataset.alwaysEdit)).catch(showError));
	}

	let auditById = new Map(), selectedAuditId = null;
	async function loadAudit() {
		const rows = await api('/api/v1/admin/audit-log?size=50'); auditById = new Map(rows.map(row => [String(row.id),row]));
		const body = $('auditRows'); body.innerHTML = rows.length ? rows.map(row => '<tr><td>' + escapeHtml(formatDate(row.createdAt)) +
			'</td><td>' + escapeHtml(String(row.actorId || '').slice(0,8)) + '</td><td>' + escapeHtml(row.action) +
			(row.restoredAt ? ' <span class="badge blue">복구됨</span>' : '') + '</td><td>' +
			escapeHtml(row.targetType ? row.targetType + ' #' + row.targetId : '-') + '</td><td>' +
			escapeHtml(row.detail || '-') + '</td><td><button class="btn" data-audit-id="' + row.id + '" ' +
			(row.beforeJson ? '' : 'disabled') + '>전후 비교</button></td></tr>').join('') :
			'<tr><td colspan="6" class="muted">기록이 없습니다.</td></tr>';
		body.querySelectorAll('[data-audit-id]').forEach(b => b.onclick = () => showAudit(b.dataset.auditId));
	}
	function prettyJson(value) { try { return JSON.stringify(JSON.parse(value),null,2); } catch { return value || '스냅샷 없음'; } }
	function showAudit(id) {
		const row = auditById.get(String(id)); if (!row) return; selectedAuditId = id;
		$('auditBefore').textContent = prettyJson(row.beforeJson); $('auditAfter').textContent = prettyJson(row.afterJson);
		const restore = $('restoreAudit'); restore.disabled = Boolean(row.restoredAt) || !['SCHOLARSHIP_UPDATE','SCHOLARSHIP_DELETE'].includes(row.action);
		restore.textContent = row.restoredAt ? '이미 복구됨' : restore.disabled ? '자동 복구 미지원' : '이 변경 이전으로 복구';
		$('auditDialog').showModal();
	}

	async function loadJobs() {
		const page = await api('/api/v1/admin/jobs?page=0&size=100'); const body = $('jobRows');
		body.innerHTML = page.content.length ? page.content.map(row => '<tr><td>' + row.id + '</td><td class="title">' +
			escapeHtml(row.jobType) + '</td><td>' + escapeHtml(row.trigger) + '</td><td>' + escapeHtml(row.status) +
			'</td><td>' + escapeHtml(formatDate(row.startedAt)) + '</td><td>' + escapeHtml(formatDate(row.finishedAt)) +
			'</td><td>' + escapeHtml(row.errorMessage || row.summary || '-') + '</td></tr>').join('') :
			'<tr><td colspan="7" class="muted">기록된 배치가 없습니다.</td></tr>';
	}
	const bytes = value => value == null ? '-' : (value/1024/1024).toFixed(0) + ' MB';
	async function loadSystem() {
		const data = await api('/api/v1/admin/system/status');
		for (const key of ['application','database','redis']) {
			const check=data[key], value=document.querySelector('[data-system="'+key+'"]');
			value.textContent=check.status; value.className='value '+(check.status==='UP'?'green':'red');
			document.querySelector('[data-system-detail="'+key+'"]').textContent=check.detail+' · '+check.latencyMs+'ms';
		}
		document.querySelector('[data-system="heap"]').textContent=data.jvmHeap.usedPercent+'%';
		document.querySelector('[data-system-detail="heap"]').textContent=bytes(data.jvmHeap.usedBytes)+' / '+bytes(data.jvmHeap.maxBytes);
		document.querySelector('[data-system="disk"]').textContent=data.disk.usedPercent+'%';
		document.querySelector('[data-system-detail="disk"]').textContent=bytes(data.disk.usedBytes)+' / '+bytes(data.disk.totalBytes);
	}
	async function loadLogs() {
		const data=await api(query('/api/v1/admin/system/logs',{lines:$('logLines').value,level:$('logLevel').value,
			keyword:$('logKeyword').value.trim()})); $('appLogs').textContent=data.available?(data.lines.join('\n')||'조건에 맞는 로그가 없습니다.'):data.message;
	}

	function addCondition() { const row=document.createElement('div');row.className='toolbar';row.innerHTML='<select data-field="conditionType"><option>UNIVERSITY_TYPE</option><option>MAJOR_FIELD</option><option>GRADE_LEVEL</option><option>ACADEMIC_CRITERIA</option><option>INCOME_CRITERIA</option><option>REGION_RESIDENCY</option><option>SPECIFIC_QUALIFICATION</option><option>RESTRICTION</option><option>FINANCIAL_AID_TYPE</option><option>RECOMMENDATION_REQUIRED</option></select><select data-field="necessity"><option>REQUIRED</option><option>PREFERRED</option></select><select data-field="operator"><option>EQ</option><option>IN</option><option>GTE</option><option>LTE</option><option>BETWEEN</option></select><input data-field="valueString" placeholder="조건 원문 *"><input data-field="refLabels" placeholder="참조 라벨(쉼표)"><button type="button" class="btn danger">삭제</button>';row.querySelector('button').onclick=()=>row.remove();$('conditionRows').appendChild(row); }
	function addDocument() { const row=document.createElement('div');row.className='toolbar';row.innerHTML='<input data-field="name" placeholder="서류명 *"><label><input data-field="essay" type="checkbox" style="min-width:auto"> 자기소개서</label><input data-field="downloadUrl" type="url" placeholder="다운로드 URL"><button type="button" class="btn danger">삭제</button>';row.querySelector('button').onclick=()=>row.remove();$('documentRows').appendChild(row); }
	function rows(container,mapper){return [...$(container).children].map(mapper);}
	async function submitManual(event){event.preventDefault();const form=event.currentTarget,value=name=>form.elements[name].value,number=name=>value(name)===''?null:Number(value(name));const payload={title:value('title').trim(),provider:textOrNull(value('provider')),summary:textOrNull(value('summary')),description:textOrNull(value('description')),scholarshipType:value('scholarshipType'),applicationStartAt:textOrNull(value('applicationStartAt')),applicationEndAt:textOrNull(value('applicationEndAt')),recruitmentStatus:textOrNull(value('recruitmentStatus')),selectionCount:number('selectionCount'),amount:number('amount'),homepageUrl:textOrNull(value('homepageUrl')),detailUrl:textOrNull(value('detailUrl')),combined:false,submissionMethod:textOrNull(value('submissionMethod')),submissionChannel:textOrNull(value('submissionChannel')),submissionEvidence:textOrNull(value('submissionEvidence')),contact:textOrNull(value('contact')),essayRequirement:textOrNull(value('essayRequirement')),essayEvidence:textOrNull(value('essayEvidence')),interviewRequirement:textOrNull(value('interviewRequirement')),interviewEvidence:textOrNull(value('interviewEvidence')),source:{sourceUrl:textOrNull(value('detailUrl')),rawHtml:textOrNull(value('rawHtml'))},conditions:rows('conditionRows',row=>({conditionType:row.querySelector('[data-field="conditionType"]').value,necessity:row.querySelector('[data-field="necessity"]').value,operator:row.querySelector('[data-field="operator"]').value,valueString:row.querySelector('[data-field="valueString"]').value.trim(),refLabels:row.querySelector('[data-field="refLabels"]').value.split(',').map(v=>v.trim()).filter(Boolean),refIds:[],refCodes:[]})),documents:rows('documentRows',(row,index)=>({name:row.querySelector('[data-field="name"]').value.trim(),essay:row.querySelector('[data-field="essay"]').checked,displayOrder:index,downloadUrl:textOrNull(row.querySelector('[data-field="downloadUrl"]').value)})),imageSourceUrl:textOrNull(value('imageSourceUrl'))};const result=await api('/api/v1/scholarships/manual/full',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});toast('장학금 #'+result.scholarshipId+' 등록 완료');form.reset();$('conditionRows').innerHTML='';$('documentRows').innerHTML='';}

	const pages=document.querySelectorAll('.page'), navButtons=document.querySelectorAll('.nav button[data-page]');
	const names={dashboard:'대시보드',intake:'신규 수집',failures:'실패 재처리',anomaly:'데이터 이상',all:'전체 장학금',always:'상시모집',duplicate:'중복 판정',images:'이미지 관리',reports:'신고·문의 처리',excel:'수기·엑셀 등록',system:'시스템 상태',batches:'배치 실행 이력',audit:'감사·복구',swagger:'Swagger'};
	const pageLoaders={dashboard:loadDashboard,intake:loadIntake,failures:loadFailures,anomaly:loadAnomalies,
		all:loadScholarships,always:loadAlways,duplicate:loadDuplicates,images:loadImages,reports:loadReports,
		system:async()=>Promise.all([loadSystem(),loadLogs()]),batches:loadJobs,audit:loadAudit};
	function showPage(id){pages.forEach(p=>p.classList.toggle('active',p.id===id));navButtons.forEach(b=>b.classList.toggle('active',b.dataset.page===id));$('pageName').textContent=names[id]||id;window.scrollTo(0,0);if(pageLoaders[id])pageLoaders[id]().catch(showError);if(id==='swagger')window.open('/swagger-ui/index.html','_blank','noopener');}

	function bindClick(id,fn){const el=$(id);if(el)el.addEventListener('click',()=>Promise.resolve(fn(el)).catch(showError));}
	navButtons.forEach(button=>button.addEventListener('click',()=>showPage(button.dataset.page)));
	document.querySelectorAll('[data-go]').forEach(button=>button.addEventListener('click',()=>showPage(button.dataset.go)));
	$('adminName').textContent=sessionStorage.getItem('wc_admin_name')||'관리자';
	bindClick('refreshDashboard',button=>busy(button,loadDashboard,'대시보드를 갱신했습니다.'));
	bindClick('refreshIntake',button=>busy(button,loadIntake,'신규 수집 목록을 갱신했습니다.'));bindClick('searchIntake',()=>{pageState.intake=0;return loadIntake();});
	bindClick('refreshFailures',button=>busy(button,loadFailures));bindClick('searchFailures',()=>{pageState.failures=0;return loadFailures();});bindClick('retryFailures',retryFailures);
	bindClick('refreshAnomalies',button=>busy(button,loadAnomalies));bindClick('searchAnomalies',()=>{pageState.anomaly=0;return loadAnomalies();});
	bindClick('searchScholarships',()=>{pageState.scholarship=0;return loadScholarships();});
	bindClick('refreshImages',button=>busy(button,loadImages));bindClick('searchImages',()=>{pageState.image=0;return loadImages();});
	bindClick('refreshDuplicates',button=>busy(button,loadDuplicates));bindClick('searchDuplicates',()=>{pageState.duplicate=0;return loadDuplicates();});bindClick('createManualMerge',createManualMerge);
	bindClick('searchMergeScholarships',searchMergeScholarships);
	bindClick('refreshReports',button=>busy(button,()=>document.querySelector('[data-report-tab="inquiry"]').classList.contains('active')?loadInquiries():loadReports()));bindClick('searchReports',()=>{pageState.report=0;return loadReports();});bindClick('searchInquiries',()=>{pageState.inquiry=0;return loadInquiries();});
	bindClick('refreshAlways',loadAlways);bindClick('refreshAudit',loadAudit);bindClick('refreshJobs',loadJobs);bindClick('refreshSystem',loadSystem);bindClick('refreshLogs',loadLogs);
	bindClick('saveImage',saveImage);bindClick('saveResolution',saveResolution);
	$('closeImageDialog').onclick=()=>$('imageDialog').close();$('closeResolveDialog').onclick=()=>$('resolveDialog').close();$('closeEditDialog').onclick=()=>$('editDialog').close();$('editForm').addEventListener('submit',event=>submitEdit(event).catch(showError));
	$('closeAuditDialog').onclick=()=>$('auditDialog').close();$('restoreAudit').onclick=async()=>{if(!selectedAuditId||!confirm('변경 전 값으로 복구할까요?'))return;await api('/api/v1/admin/audit-log/'+selectedAuditId+'/restore',{method:'PATCH'});$('auditDialog').close();await loadAudit();};
	document.querySelectorAll('[data-report-tab]').forEach(button=>button.onclick=()=>{document.querySelectorAll('[data-report-tab]').forEach(b=>b.classList.toggle('active',b===button));$('scholarshipReportPanel').hidden=button.dataset.reportTab!=='scholarship';$('inquiryPanel').hidden=button.dataset.reportTab!=='inquiry';(button.dataset.reportTab==='inquiry'?loadInquiries():loadReports()).catch(showError);});
	$('scholarshipKeyword').addEventListener('keydown',e=>{if(e.key==='Enter'){pageState.scholarship=0;loadScholarships().catch(showError);}});
	$('globalSearch').addEventListener('keydown',e=>{if(e.key==='Enter'){$('scholarshipKeyword').value=e.currentTarget.value;pageState.scholarship=0;showPage('all');}});
	$('systemAlert').onclick=()=>showPage('batches');$('addCondition').onclick=addCondition;$('addDocument').onclick=addDocument;$('manualFullForm').addEventListener('submit',event=>submitManual(event).catch(showError));
	$('logout').onclick=async()=>{try{await fetch('/api/v1/admin/auth/logout',{method:'POST'});}finally{sessionStorage.clear();location.replace('/admin/login.html');}};

	async function downloadFile(path,name){const response=await fetch(path,{headers:{Authorization:'Bearer '+token}});if(!response.ok)throw new Error('파일을 받지 못했습니다.');const blob=await response.blob(),url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download=name;link.click();URL.revokeObjectURL(url);}
	bindClick('exportExcel',()=>downloadFile('/api/v1/scholarships/admin/excel','scholarships-'+new Date().toISOString().slice(0,10)+'.xlsx'));
	bindClick('manualTemplate',()=>downloadFile('/api/v1/scholarships/admin/manual-excel/template','scholarship-manual-template.xlsx'));
	async function importManualExcel(dryRun){const file=$('manualExcelFile').files[0];if(!file)throw new Error('xlsx 파일을 선택하세요.');const form=new FormData();form.append('file',file);const result=await api('/api/v1/scholarships/admin/manual-excel?dryRun='+dryRun,{method:'POST',body:form});$('manualExcelResult').textContent=(dryRun?'검사':'반영')+' 완료 · 대상 '+result.totalRows+'건 · 처리 '+result.createdRows+'건 · 오류 '+result.errorCount+'건';$('manualExcelApply').disabled=!(dryRun&&result.createdRows>0&&result.errorCount===0);}
	bindClick('manualExcelDryRun',()=>importManualExcel(true));bindClick('manualExcelApply',()=>{if(confirm('실제 DB에 등록할까요?'))return importManualExcel(false);});$('manualExcelFile').onchange=()=>$('manualExcelApply').disabled=true;

	const yesterday=new Date();yesterday.setDate(yesterday.getDate()-1);$('intakeDate').value=yesterday.toLocaleDateString('en-CA');
	Promise.all([loadDashboard(),loadAudit()]).catch(showError);
})();
