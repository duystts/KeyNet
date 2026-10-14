// Dong bo label "san sang" / "cho issue khac" theo Lo trinh (issue ROADMAP_ISSUE)
// va danh dau checklist trong Lo trinh cho cac Issue da dong.
//
// Dinh dang 1 dong trong Lo trinh:
//   - [ ] #6 CacheClient — Hung · lam sau khi: #4, #5
// Dong khong co "lam sau khi" = lam ngay duoc -> "san sang" khi con mo.

const READY = 'sẵn sàng';
const WAITING = 'chờ issue khác';

const LINE = /^(\s*-\s*\[)([ xX])(\]\s*#(\d+))(.*)$/;
const DEPS = /làm sau khi:\s*((?:#\d+\s*,?\s*)+)/i;

/** Doc Lo trinh: tra ve Map so Issue -> danh sach Issue phai xong truoc (rong neu khong co). */
function parseRoadmap(body) {
  const items = new Map();
  for (const line of body.split('\n')) {
    const m = line.match(LINE);
    if (!m) continue;
    const issue = Number(m[4]);
    const d = m[5].match(DEPS);
    const deps = d ? [...d[1].matchAll(/#(\d+)/g)].map((x) => Number(x[1])) : [];
    items.set(issue, deps);
  }
  return items;
}

/** Danh dau [x] cho Issue da dong, [ ] cho Issue dang mo. */
function tickChecklist(body, isClosed) {
  return body
    .split('\n')
    .map((line) => {
      const m = line.match(LINE);
      if (!m) return line;
      const closed = isClosed(Number(m[4]));
      if (closed === undefined) return line;
      return m[1] + (closed ? 'x' : ' ') + m[3] + m[5];
    })
    .join('\n');
}

/** Label can co cua 1 Issue: null = Issue da dong, go ca 2 label trang thai. */
function wantedLabel(issueClosed, deps, isClosed) {
  if (issueClosed) return null;
  return deps.every((d) => isClosed(d)) ? READY : WAITING;
}

async function run({ github, context, core }) {
  const { owner, repo } = context.repo;
  const roadmapNumber = Number(process.env.ROADMAP_ISSUE);
  const roadmap = (await github.rest.issues.get({ owner, repo, issue_number: roadmapNumber })).data;
  const items = parseRoadmap(roadmap.body || '');

  // Trang thai (dong/mo) va label hien tai cua moi Issue nhac toi trong Lo trinh
  const numbers = new Set(items.keys());
  for (const deps of items.values()) deps.forEach((d) => numbers.add(d));
  const info = new Map();
  for (const n of numbers) {
    try {
      const { data } = await github.rest.issues.get({ owner, repo, issue_number: n });
      info.set(n, { closed: data.state === 'closed', labels: data.labels.map((l) => l.name) });
    } catch (e) {
      core.warning(`Khong doc duoc #${n}: ${e.message}`);
    }
  }
  const isClosed = (n) => (info.has(n) ? info.get(n).closed : undefined);

  for (const [n, deps] of items) {
    const cur = info.get(n);
    if (!cur) continue;
    const want = wantedLabel(cur.closed, deps, (d) => isClosed(d) === true);
    for (const label of [READY, WAITING]) {
      const has = cur.labels.includes(label);
      if (label === want && !has) {
        await github.rest.issues.addLabels({ owner, repo, issue_number: n, labels: [label] });
        core.info(`#${n}: + ${label}`);
      } else if (label !== want && has) {
        await github.rest.issues.removeLabel({ owner, repo, issue_number: n, name: label });
        core.info(`#${n}: - ${label}`);
      }
    }
  }

  const newBody = tickChecklist(roadmap.body || '', isClosed);
  if (newBody !== roadmap.body) {
    await github.rest.issues.update({ owner, repo, issue_number: roadmapNumber, body: newBody });
    core.info(`Da cap nhat checklist #${roadmapNumber}`);
  }
}

module.exports = run;
module.exports.parseRoadmap = parseRoadmap;
module.exports.tickChecklist = tickChecklist;
module.exports.wantedLabel = wantedLabel;
