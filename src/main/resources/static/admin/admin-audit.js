/*
 * 관리자 콘솔 "작업 기록"(감사 로그) 표시. admin-core.js(WC) 다음, admin-console.js 앞에 읽는다.
 *
 * 아래 "순수 함수"는 DOM 을 쓰지 않아 node 로 테스트한다(src/test/resources/admin-js/audit.test.mjs).
 */
(root => {
	'use strict';

	const WC = root.WC;
	const {L} = WC;

	/** 행위 코드 → {known, label, code}. 모르는 코드(새로 생긴 작업)는 코드를 그대로 이름으로 쓴다. */
	function actionView(code) {
		const name = (L.action || {})[code];
		return {known: Boolean(name), label: name || String(code || '-'), code: String(code || '')};
	}

	const scholarshipName = (row, id) => {
		const ref = (row.scholarships || {})[id];
		return '#' + id + ' ' + (ref && ref.title ? ref.title : '(제목을 찾지 못함)') + (ref && ref.deleted ? ' (내려짐)' : '');
	};

	WC.audit = {actionView, scholarshipName};
})(typeof window !== 'undefined' ? window : globalThis);
