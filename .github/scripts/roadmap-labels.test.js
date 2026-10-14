// Chay: node --test .github/scripts/
const test = require('node:test');
const assert = require('node:assert');
const run = require('./roadmap-labels.js');
const { parseRoadmap, tickChecklist, wantedLabel } = run;

const BODY = [
  '### Tuần 1',
  '- [x] #1 Khung dự án — Duy',
  '- [ ] #4 CacheServer — Duy (PR #24 chờ review)',
  '- [ ] #5 HashRouter — Hùng · **làm ngay**',
  '- [ ] #6 CacheClient — Hùng · làm sau khi: #4, #5',
  '- [ ] #13 Tích hợp cụm — Duy · làm sau khi: #4, #9, #10, #12',
  '- [ ] #14 GUI Dashboard — Nhật · **làm ngay với dữ liệu giả**, nối Registry thật sau #11',
  'Đường găng: `#4 → #8`',
].join('\n');

test('doc dung danh sach phu thuoc', () => {
  const items = parseRoadmap(BODY);
  assert.deepStrictEqual(items.get(6), [4, 5]);
  assert.deepStrictEqual(items.get(13), [4, 9, 10, 12]);
  assert.deepStrictEqual(items.get(4), [], 'so PR trong ngoac khong phai phu thuoc');
  assert.deepStrictEqual(items.get(14), [], '"sau #11" khong phai cu phap "làm sau khi"');
  assert.strictEqual(items.has(8), false, 'dong khong phai checklist thi bo qua');
});

test('chon label dung', () => {
  const closed = new Set([4]);
  const isClosed = (n) => closed.has(n);
  assert.strictEqual(wantedLabel(false, [4, 5], isClosed), 'chờ issue khác');
  closed.add(5);
  assert.strictEqual(wantedLabel(false, [4, 5], isClosed), 'sẵn sàng');
  assert.strictEqual(wantedLabel(true, [4, 5], isClosed), null, 'issue da dong thi bo label');
  assert.strictEqual(wantedLabel(false, [], isClosed), 'sẵn sàng', 'khong phai cho ai thi lam ngay duoc');
});

test('danh dau checklist theo trang thai', () => {
  const states = { 1: false, 4: true, 5: true };
  const out = tickChecklist(BODY, (n) => states[n]);
  assert.match(out, /- \[ \] #1 Khung/, 'issue mo lai thi bo dau');
  assert.match(out, /- \[x\] #4 CacheServer/);
  assert.match(out, /- \[ \] #6 CacheClient/, 'khong biet trang thai thi giu nguyen');
  assert.match(out, /Đường găng: `#4 → #8`/);
});

test('chay toan bo voi GitHub gia lap', async () => {
  const issues = {
    27: { state: 'open', labels: [], body: BODY },
    1: { state: 'closed', labels: [] },
    4: { state: 'closed', labels: [] },
    5: { state: 'closed', labels: [{ name: 'sẵn sàng' }] },
    6: { state: 'open', labels: [{ name: 'chờ issue khác' }] },
    9: { state: 'open', labels: [] }, 10: { state: 'open', labels: [] }, 12: { state: 'open', labels: [] },
    13: { state: 'open', labels: [] },
    14: { state: 'open', labels: [{ name: 'sẵn sàng' }] },
  };
  const calls = [];
  const github = { rest: { issues: {
    get: async ({ issue_number }) => ({ data: { ...issues[issue_number] } }),
    addLabels: async ({ issue_number, labels }) => calls.push(`#${issue_number} +${labels[0]}`),
    removeLabel: async ({ issue_number, name }) => calls.push(`#${issue_number} -${name}`),
    update: async ({ issue_number, body }) => { issues[issue_number].body = body; calls.push(`#${issue_number} body`); },
  } } };
  process.env.ROADMAP_ISSUE = '27';
  await run({ github, context: { repo: { owner: 'o', repo: 'r' } }, core: { info() {}, warning() {} } });
  assert.deepStrictEqual(calls.sort(), [
    '#13 +chờ issue khác',   // #9, #10, #12 chua xong
    '#27 body',
    '#5 -sẵn sàng',          // da dong
    '#6 +sẵn sàng',          // #4, #5 da dong
    '#6 -chờ issue khác',
  ].sort());
  assert.match(issues[27].body, /- \[x\] #4 CacheServer/);
  assert.match(issues[27].body, /- \[x\] #5 HashRouter/);
  assert.match(issues[27].body, /- \[ \] #6 CacheClient/);
});
