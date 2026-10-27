// 작업 기록(admin-audit.js) 순수 함수 테스트. AdminConsoleScriptTest 가 `node --test` 로 실행한다(node 18+).
// 브라우저 없이 admin-core.js → admin-audit.js 순서로 읽는다. admin-core 는 읽을 때 sessionStorage 만 쓴다.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {fileURLToPath} from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const staticDir = path.resolve(here, '../../../main/resources/static/admin');
const context = {sessionStorage: {getItem: () => null, setItem() {}, removeItem() {}}, console, Intl, Date, Math, JSON};
context.window = context;
vm.createContext(context);
for (const file of ['admin-core.js', 'admin-audit.js']) {
	vm.runInContext(fs.readFileSync(path.join(staticDir, file), 'utf8'), context, {filename: file});
}
const {fmt} = context.WC;
const A = context.WC.audit;
const plain = value => JSON.parse(JSON.stringify(value));

test('날짜는 "2026.11.11 (수) 23:59", 초는 이전·이후가 초만 다를 때만', () => {
	assert.equal(fmt.bizDay('2026-11-11T23:59:59'), '2026.11.11 (수) 23:59');
	assert.equal(A.formatValue('applicationEndAt', '2026-11-11T23:59:59', '2026-11-12T00:00:00'), '2026.11.11 (수) 23:59');
	assert.equal(A.formatValue('applicationEndAt', '2026-11-11T23:59:59', '2026-11-11T23:59:00'), '2026.11.11 (수) 23:59:59');
	assert.equal(A.formatValue('applicationEndAt', '2026-11-11T23:59', '2026-11-11T23:59:59'), '2026.11.11 (수) 23:59:00');
	assert.equal(fmt.bizDay('2026-10-25'), '2026.10.25 (일)');
});

test('금액·인원·참거짓·enum·빈 값 형식', () => {
	assert.equal(A.formatValue('amount', 1000000), '1,000,000원');
	assert.equal(A.formatValue('selectionCount', 12), '12명');
	assert.equal(A.formatValue('combined', true), '예');
	assert.equal(A.formatValue('periodLocked', false), '아니오');
	assert.equal(A.formatValue('recruitmentStatus', 'CLOSED'), '마감');
	assert.equal(A.formatValue('essayRequirement', 'REQUIRED'), '필수');
	assert.equal(A.formatValue('summary', null), '(비어 있음)');
	assert.equal(A.formatValue('summary', ''), '(비어 있음)');
});

test('조건은 문장형, enum 은 한글(REQUIRED → 필수 자격, GTE → 이상)', () => {
	const sentence = A.conditionSentence({conditionType: 'ACADEMIC_CRITERIA', necessity: 'REQUIRED', operator: 'GTE',
		valueString: '직전 학기까지 누계 평균 학점 3.5이상(4.5만점 기준)', valueInt: 35});
	assert.equal(sentence, '성적 · 필수 자격 · 이상 · 직전 학기까지 누계 평균 학점 3.5이상(4.5만점 기준) (35)');
});

test('필드 라벨은 내부 경로가 아니라 한글, 시스템 필드는 자동 변경 항목으로', () => {
	const before = {scholarship: {title: '가', applicationEndAt: '2026-11-11T23:59:59', updatedAt: '2026-10-01T10:00:00.123456',
		verified: false, amount: 100}, conditions: [], documents: []};
	const after = {scholarship: {title: '나', applicationEndAt: '2026-11-11T23:59:59', updatedAt: '2026-10-02T10:00:00.5',
		verified: true, amount: 100}, conditions: [], documents: []};
	const result = plain(A.diffSnapshots(before, after));
	assert.deepEqual(result.changes.map(c => c.label), ['장학금명']);
	assert.deepEqual(result.system.map(c => c.label).sort(), ['검수 완료', '마지막 수정 시각'].sort());
	assert.equal(result.changes[0].before, '가');
	assert.equal(result.changes[0].after, '나');
});

test('같은 초 안의 마이크로초 차이는 바뀐 것으로 보지 않는다', () => {
	const result = A.diffSnapshots({scholarship: {updatedAt: '2026-10-27T09:07:51.489576'}},
		{scholarship: {updatedAt: '2026-10-27T09:07:51.602131'}});
	assert.equal(result.changes.length + result.system.length, 0);
});

test('한쪽 기록에만 있는 필드(선발 일정·기간 고정)는 비교하지 않는다 — 없음 ≠ 빈 목록', () => {
	const result = A.diffSnapshots({scholarship: {title: 'a'}, conditions: []},
		{scholarship: {title: 'a', periodLocked: true}, conditions: [], timeline: [{stageCode: 'INTERVIEW', dateType: 'TBD', dateText: '12월'}]});
	assert.equal(result.changes.length, 0);
});

test('목록은 행 ID 가 아니라 내용으로 같은 행을 찾는다', () => {
	const cond = (id, valueString, extra = {}) => Object.assign({id, conditionType: 'ACADEMIC_CRITERIA', necessity: 'REQUIRED', operator: 'GTE',
		valueString, valueInt: 35, autoExtracted: true, refs: []}, extra);
	const income = id => ({id, conditionType: 'INCOME_CRITERIA', necessity: 'PREFERRED', operator: 'LTE', valueString: '8분위', valueInt: 8,
		autoExtracted: true, refs: []});
	// 저장할 때마다 ID 가 바뀌어도(1→11, 2→12) 내용이 같으면 그대로인 행
	const same = A.listDiff('conditions', [cond(1, '3.5 이상'), income(2)], [income(12), cond(11, '3.5 이상')]);
	assert.equal(same.total, 0);
	assert.equal(same.orderOnly, true);
	// 같은 유형끼리는 변경(~), 새 유형은 추가(+), 빠진 것은 삭제(−)
	const diff = A.listDiff('conditions', [cond(1, '3.5 이상'), income(2)],
		[cond(11, '3.0 이상', {valueInt: 30}), {id: 13, conditionType: 'REGION_RESIDENCY', necessity: 'REQUIRED', operator: 'EQ', valueString: '경기'}]);
	assert.equal(diff.changed.length, 1);
	assert.equal(diff.changed[0].after.valueString, '3.0 이상');
	assert.equal(diff.added.length, 1);
	assert.equal(diff.removed.length, 1);
	assert.equal(diff.removed[0].conditionType, 'INCOME_CRITERIA');
	// 참조(refs)는 순서가 달라도 같다
	const refs = A.listDiff('conditions', [cond(1, 'x', {refs: [{refId: 2}, {refCode: 'A'}]})], [cond(9, 'x', {refs: [{refCode: 'A'}, {refId: 2}]})]);
	assert.equal(refs.total, 0);
});

test('서류·선발 일정도 내용 기준으로 대조한다', () => {
	const docs = A.listDiff('documents', [{id: 1, name: '성적증명서', essay: false}], [{id: 5, name: '성적증명서', essay: false}, {id: 6, name: '추천서'}]);
	assert.equal(docs.same.length, 1);
	assert.equal(docs.added.length, 1);
	const timeline = A.listDiff('timeline', [{id: 1, stageCode: 'INTERVIEW', dateType: 'SINGLE', startDate: '2026-12-01', origin: 'LLM', displayOrder: 0}],
		[{id: 2, stageCode: 'INTERVIEW', dateType: 'SINGLE', startDate: '2026-12-01', origin: 'MANUAL', displayOrder: 3}]);
	assert.equal(timeline.total, 0, '출처·순서·ID 만 다르면 같은 일정');
	assert.equal(A.timelineSentence({stageCode: 'INTERVIEW', dateType: 'TBD', dateText: '12월 중 예정'}), '면접 · 12월 중 예정');
});

test('긴 글은 단어 단위로 추가·삭제를 찾는다', () => {
	const parts = plain(A.wordDiff('성적 우수 학생을 위한 장학금', '성적 우수 학생과 저소득층 학생을 위한 장학금'));
	assert.equal(parts.filter(p => p.op === '-').length, 0);
	assert.equal(parts.filter(p => p.op === '+').map(p => p.text).join('').trim(), '학생과 저소득층');
	assert.equal(parts.filter(p => p.op !== '-').map(p => p.text).join(''), '성적 우수 학생과 저소득층 학생을 위한 장학금');
});

test('모르는 action 은 코드를 그대로 이름으로 쓰는 일반 형식', () => {
	assert.deepEqual(plain(A.actionView('SCHOLARSHIP_AGGREGATE_UPDATE')), {known: true, label: '장학금 통합 수정', code: 'SCHOLARSHIP_AGGREGATE_UPDATE'});
	assert.deepEqual(plain(A.actionView('RAW_SCHOLARSHIP_SOMETHING_NEW')), {known: false, label: 'RAW_SCHOLARSHIP_SOMETHING_NEW', code: 'RAW_SCHOLARSHIP_SOMETHING_NEW'});
	assert.equal(A.actionView('SCHOLARSHIP_DELETE').label, '장학금 내리기');
	assert.equal(A.actionView('SCHOLARSHIP_MERGE_REJECT').label, '중복 아님 처리');
	assert.equal(A.actionView('SCHOLARSHIP_CREATE').label, '장학금 등록');
});

test('병합·중복 아님 요약을 읽는다(일괄 표시·잘린 요약 포함)', () => {
	const merged = plain(A.parseMergeDetail('SCHOLARSHIP_MERGE', '일괄 병합 12건 중 3번째 · 중복 307 를 683 로 병합. {scrap.moved=2, essay.moved=0, image.mov'));
	assert.deepEqual(merged.bulk, {total: 12, position: 3});
	assert.equal(merged.keepId, 683);
	assert.equal(merged.dropId, 307);
	assert.deepEqual(merged.moved.map(m => [m.label, m.count]), [['스크랩', 2], ['자기소개서', 0]]);
	const rejected = plain(A.parseMergeDetail('SCHOLARSHIP_MERGE_REJECT', '중복 후보 12 반려 (683 vs 644). 캠퍼스가 다름'));
	assert.deepEqual([rejected.candidateId, rejected.keepId, rejected.dropId, rejected.note], [12, 683, 644, '캠퍼스가 다름']);
	assert.equal(A.parseMergeDetail('SCHOLARSHIP_MERGE', '알 수 없는 문구'), null);
});

test('등록 내용: 입력하지 않은 항목을 모으고, 스냅샷이 없는 옛 기록은 안내만', () => {
	const view = plain(A.snapshotSections({scholarship: {title: '새 장학금', provider: null, amount: null, applicationStartAt: '2026-10-01T00:00:00',
		applicationEndAt: '2026-11-11T23:59:59', homepageUrl: '', recruitmentStatus: 'OPEN'}, conditions: [], documents: [{name: '성적증명서'}], timeline: []}));
	assert.ok(view.missing.includes('지원 금액'));
	assert.ok(view.missing.includes('홈페이지 URL'));
	assert.ok(view.missing.includes('지원 조건'));
	assert.ok(!view.missing.includes('제출서류'));
	assert.equal(view.sections[0].rows.find(r => r.key === 'recruitmentStatus').value, '모집 중');
	assert.equal(A.snapshotSections(null), null);
	const legacy = A.createBodyHtml({action: 'SCHOLARSHIP_CREATE', targetId: 725, afterJson: null, detail: '통합 수기 등록'});
	assert.match(legacy, /등록 당시 내용이 기록되지 않았습니다/);
	assert.match(legacy, /data-audit-open-current="725"/);
});

test('기록 종류별 상세 버튼', () => {
	assert.equal(A.detailKind({action: 'SCHOLARSHIP_CREATE'}).label, '등록 내용');
	assert.equal(A.detailKind({action: 'SCHOLARSHIP_DELETE', beforeJson: '{}'}).label, '내리기 직전 내용');
	assert.equal(A.detailKind({action: 'SCHOLARSHIP_MERGE'}).label, '병합 내용');
	assert.equal(A.detailKind({action: 'SCHOLARSHIP_AGGREGATE_UPDATE', beforeJson: '{}', afterJson: '{}'}).label, '전후 비교');
	assert.equal(A.detailKind({action: 'SYNC_TRIGGER'}), null);
});
