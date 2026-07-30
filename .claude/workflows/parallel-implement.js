export const meta = {
  name: 'parallel-implement',
  description: '複数タスクを「触るファイル」から自動でレーン分割し、衝突しない組み合わせだけを git worktree で並列実装する',
  whenToUse:
    '独立してそうな実装タスクが複数あるとき。args にタスク説明の配列を渡す。' +
    '実装前に各タスクが触るファイルを調べ、重なったタスクは同じレーン（＝直列）に落として衝突を消してから並列化する。',
  phases: [
    { title: 'Scout', detail: 'タスクごとに「触るファイル」を調査（コードは変更しない）' },
    { title: 'Implement', detail: 'レーンごとに worktree を切って並列実装＋コミット' },
    { title: 'Verify', detail: 'レーンごとに差分を反証レビュー' },
  ],
}

// ---------------------------------------------------------------------------
// 設計の要点
//
// 並列化で失敗する原因は「機能で割ってしまうこと」。機能として独立していても、
// 両方が TrinityForge.java の配線や同じ yml を触るなら worktree を分けても必ず衝突する。
// そこで **実装前に「触るファイル」を調べ、ファイル集合が交わるタスクを同じレーンへ落とす**。
// レーン分割は素の union-find（連結成分）で決まる — ここはモデルに判断させず JS でやる。
// 詳細は docs/agent-context/parallel-worktrees.md
// ---------------------------------------------------------------------------

const MAX_TASKS = 8
const BASE_BRANCH = 'dev'

// 触らせないファイル（全員が末尾に追記するので必ず衝突する。統合役がマージ後に1回だけ書く）
const FORBIDDEN = ['reports/ACTIVE_RECORD.md', '.gitattributes']

// worktree に存在しない／並列化してはいけない領域。ここに当たるタスクは実装せず差し戻す。
const SERIAL_ONLY = [
  { pattern: /^fork-handoff\//, why: 'フォークのソースは .gitignore 除外なので worktree に存在しない' },
  { pattern: /^ops\//, why: '配備・運用は対象が1セットしかなく並列化できない' },
  { pattern: /^tmp\/.*\.cmd$/, why: '配備スクリプトは直列に扱う' },
]

const SCOUT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['files', 'domain', 'plan'],
  properties: {
    files: {
      type: 'array',
      items: { type: 'string' },
      description:
        'このタスクで変更・新規作成する可能性のあるファイルのリポジトリ相対パス。' +
        '**足りないより多い方が安全**（漏らすと後で衝突する）。テストファイルも含める。',
    },
    domain: {
      type: 'string',
      enum: ['combat', 'progression', 'editor', 'forks', 'ops', 'bedrock'],
    },
    plan: { type: 'string', description: '実装方針。何をどう変えるか' },
    blocked: { type: 'string', description: '着手できない理由があれば（仕様が決まっていない等）' },
  },
}

const IMPL_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['done', 'branch', 'changedFiles', 'testOutput'],
  properties: {
    done: { type: 'boolean' },
    branch: { type: 'string', description: 'コミットしたブランチ名' },
    changedFiles: { type: 'array', items: { type: 'string' } },
    testOutput: { type: 'string', description: 'テストの実出力（pass/fail/skip の数を含む）' },
    wiringNeeded: {
      type: 'string',
      description: 'TrinityForge.java などの共有ファイルに必要な配線。自分では書かず、ここに文章で残す',
    },
    notes: { type: 'string' },
  },
}

const REVIEW_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['findings'],
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['title', 'detail', 'severity'],
        properties: {
          title: { type: 'string' },
          detail: { type: 'string' },
          severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low'] },
        },
      },
    },
  },
}

const DOMAIN_DOCS = {
  combat: 'docs/agent-context/combat.md',
  progression: 'docs/agent-context/progression-skilltree.md',
  editor: 'docs/agent-context/config-editor.md',
  forks: 'docs/agent-context/forks-and-mobs.md',
  ops: 'docs/agent-context/ops-build-deploy.md',
  bedrock: 'docs/agent-context/bedrock-geyser.md',
}

const input = Array.isArray(args) ? args : args ? [String(args)] : []
if (!input.length) {
  log('args にタスク説明の配列を渡してください。例: ["採取EXPの…を直す", "editor に…を足す"]')
  return { lanes: [], note: 'no input' }
}
let tasks = input.map((t, i) => ({ id: i, text: String(t) }))
if (tasks.length > MAX_TASKS) {
  log(`タスク ${tasks.length} 件のうち先頭 ${MAX_TASKS} 件のみ扱います。`)
  tasks = tasks.slice(0, MAX_TASKS)
}

// --- Phase 1: 触るファイルを調べる（読み取り専用） ---------------------------
phase('Scout')
const scouted = await parallel(
  tasks.map((task) => () =>
    agent(
      `TrinityForge（Minecraft Paper 1.21.11 / Java 21）の実装タスクについて、**実装前の下調べ**をしてください。
**コードは1行も変更しないこと。** このフェーズの成果物は「触るファイルのリスト」です。

## タスク
${task.text}

## 手順
1. \`CLAUDE.md\` と \`docs/agent-context/README.md\` を読み、このタスクがどの領域かを判断する。
2. その領域の恒久知識文書を読む（\`docs/agent-context/*.md\`）。
3. 実コードを検索して、**実際に変更・新規作成することになるファイルを全部挙げる**。
   忘れやすいもの:
   - 対応するテストファイル（\`TrinityForge/src/test/java/...\`）
   - yml を足すなら \`TrinityForge/src/main/resources/**\` と \`config/domains/*Config.java\`
   - さらに \`tools/config-editor/lib/constants.js\` と \`tools/config-editor/public/js/constants.js\`（**2本セット**）
   - リスナーを新設するなら \`TrinityForge/src/main/java/com/trinityforge/TrinityForge.java\`（配線）
4. **迷ったら挙げる。** リストから漏れたファイルは後で他のレーンと衝突します。逆に多めに挙げても
   （そのタスクが直列に落ちるだけで）壊れません。

## 注意
- \`reports/ACTIVE_RECORD.md\` は**触らない**ので files に入れないこと（統合役がまとめて書きます）。
- 仕様が決まっておらず着手できない場合は blocked に理由を書くこと。`,
      { label: `scout:${task.id}`, phase: 'Scout', schema: SCOUT_SCHEMA }
    ).then((r) => ({ ...task, ...(r ?? {}) }))
  )
)

const ok = scouted.filter((t) => t && Array.isArray(t.files))
const failedScout = tasks.filter((t) => !ok.some((o) => o.id === t.id))
const blocked = ok.filter((t) => t.blocked)
const runnable = ok.filter((t) => !t.blocked)

// SERIAL_ONLY に当たるタスクは worktree で実装できないので差し戻す
const serial = []
const parallelizable = []
for (const t of runnable) {
  const hit = t.files.map((f) => SERIAL_ONLY.find((s) => s.pattern.test(f))).find(Boolean)
  if (hit) serial.push({ ...t, why: hit.why })
  else parallelizable.push(t)
}

// --- Phase 2: レーン分割（モデルを使わない。ファイル集合の連結成分） -----------
// 触るファイルが1つでも重なるタスクは同じレーンへ。レーン内は1エージェントが直列に実装する。
const parent = parallelizable.map((_, i) => i)
const find = (i) => (parent[i] === i ? i : (parent[i] = find(parent[i])))
const union = (a, b) => {
  const ra = find(a)
  const rb = find(b)
  if (ra !== rb) parent[rb] = ra
}
const norm = (f) => String(f).replace(/\\/g, '/').replace(/^\.\//, '')
for (let i = 0; i < parallelizable.length; i++) {
  for (let j = i + 1; j < parallelizable.length; j++) {
    const a = new Set(parallelizable[i].files.map(norm))
    if (parallelizable[j].files.map(norm).some((f) => a.has(f))) union(i, j)
  }
}
const laneMap = new Map()
parallelizable.forEach((t, i) => {
  const root = find(i)
  if (!laneMap.has(root)) laneMap.set(root, [])
  laneMap.get(root).push(t)
})
const lanes = [...laneMap.values()].map((members, i) => ({
  name: `wave-${i + 1}`,
  branch: `work/wave-${i + 1}`,
  members,
  files: [...new Set(members.flatMap((m) => m.files.map(norm)))],
}))

log(`タスク ${tasks.length} 件 → 並列 ${lanes.length} レーン / 直列送り ${serial.length} 件 / 着手不可 ${blocked.length} 件`)
for (const lane of lanes) {
  log(`  ${lane.name}: ${lane.members.length} タスク, ${lane.files.length} ファイル`)
}
if (serial.length) log(`直列送り（worktree では実装できない）: ${serial.map((s) => s.why).join(' / ')}`)
if (!lanes.length) {
  return { lanes: [], serial, blocked, failedScout: failedScout.map((t) => t.text) }
}

// --- Phase 3 & 4: レーンごとに worktree で実装 → そのまま反証レビュー ---------
const results = await pipeline(
  lanes,
  (lane) => {
    const docs = [...new Set(lane.members.map((m) => DOMAIN_DOCS[m.domain]).filter(Boolean))]
    return agent(
      `TrinityForge（Minecraft Paper 1.21.11 / Java 21）の実装タスクを実行してください。
**あなたは専用の git worktree の中にいます。** 他のレーンが同時に別の worktree で作業しているので、
**下に列挙されたファイル以外は絶対に変更しないでください。**

## タスク（この順に、1つずつ完了させる）
${lane.members.map((m, i) => `### ${i + 1}. ${m.text}\n下調べ済みの方針: ${m.plan ?? '(なし)'}`).join('\n\n')}

## あなたが所有するファイル（これ以外を変更しない）
${lane.files.map((f) => `- ${f}`).join('\n')}
下調べで挙がっていないファイルを変更する必要が出た場合は、**変更せずに notes に書いて報告**してください
（他のレーンが同じファイルを持っている可能性があります）。

## 着手前に必ず読むもの（全文）
${docs.map((d) => `- \`${d}\``).join('\n')}
- \`docs/agent-context/common-traps.md\`
- \`CLAUDE.md\`

## 禁止事項
- \`${FORBIDDEN.join('\` / \`')}\` の変更（統合役がマージ後にまとめて書きます）
- \`TrinityForge.java\` の配線を**所有ファイルに含まれていないのに**書くこと。
  必要な配線は \`wiringNeeded\` に文章で残してください。
- \`git add -A\` / \`git commit -a\`。**触ったパスだけを列挙して add する。**
- \`D:/game/minecraft/...\` への書き込み。稼働中サーバへの jar 差し替え。

## 完了手順
1. 実装する。
2. テストを実走する:
   \`\`\`bash
   cd TrinityForge && ./gradlew test --offline "-Dorg.gradle.java.home=C:\\Program Files\\Java\\jdk-21"
   \`\`\`
   （editor を触ったなら \`cd tools/config-editor && npm test\` も）
   **スキップ数も必ず見ること。** MockBukkit は未実装 API を失敗ではなく SKIPPED に化けさせます。
   **注意: このリポジトリは他セッションの未コミット変更のせいで元から落ちているテストがあります。**
   着手前にベースラインを取り、自分の変更由来の失敗かを切り分けてください。
3. ブランチを切ってコミットする（worktree の ref は共有されるので、統合役がここから取り込みます）:
   \`\`\`bash
   git switch -c ${lane.branch}
   git add <触ったパスを列挙>
   git commit -m "<type>: <説明>"
   \`\`\`
4. \`testOutput\` にテストの**実出力**を貼る。「通ったはず」は報告として無効です。`,
      {
        label: `impl:${lane.name}`,
        phase: 'Implement',
        schema: IMPL_SCHEMA,
        isolation: 'worktree',
      }
    ).then((r) => ({ lane, impl: r }))
  },
  (prev) => {
    if (!prev?.impl?.done) return prev
    return agent(
      `別のエージェントが実装した差分を**反証レビュー**してください。**コードは変更しないこと。**

## 対象ブランチ
\`${prev.impl.branch}\`（ベース: \`${BASE_BRANCH}\`）
差分は \`git diff ${BASE_BRANCH}...${prev.impl.branch}\` で読めます。

## 実装されたタスク
${prev.lane.members.map((m) => `- ${m.text}`).join('\n')}

## 着手前に必ず読むもの
${[...new Set(prev.lane.members.map((m) => DOMAIN_DOCS[m.domain]).filter(Boolean))].map((d) => `- \`${d}\``).join('\n')}
- \`docs/agent-context/common-traps.md\`

## 見る観点
1. **恒久知識に書かれている落とし穴を踏んでいないか**（これが最重要）。
2. 所有していないファイルを触っていないか（\`git diff --name-only ${BASE_BRANCH}...${prev.impl.branch}\`）。
   許可されていたのは: ${prev.lane.files.join(', ')}
3. **既存テストの期待値を「通すために」書き換えていないか。**
4. 新しいテストが本当に走っているか（SKIPPED に化けていないか）。
5. yml を足したなら Java の SchemaField と config-editor のミラー2本にも入っているか。

指摘は「どういう入力・状態で何が起きるか」まで具体的に書き、**確信が持てないものは挙げないでください**。
問題が無ければ findings を空配列で返してください。`,
      { label: `verify:${prev.lane.name}`, phase: 'Verify', schema: REVIEW_SCHEMA }
    ).then((v) => ({ ...prev, review: v }))
  }
)

const done = results.filter(Boolean)
const blockingFindings = done.flatMap((d) =>
  (d.review?.findings ?? [])
    .filter((f) => f.severity === 'critical' || f.severity === 'high')
    .map((f) => ({ lane: d.lane.name, ...f }))
)

return {
  // 統合役（メインのワークツリー）がこの順に merge する
  mergeOrder: done.filter((d) => d.impl?.done).map((d) => d.impl.branch),
  lanes: done.map((d) => ({
    lane: d.lane.name,
    branch: d.impl?.branch ?? null,
    tasks: d.lane.members.map((m) => m.text),
    done: d.impl?.done ?? false,
    changedFiles: d.impl?.changedFiles ?? [],
    testOutput: d.impl?.testOutput ?? '',
    wiringNeeded: d.impl?.wiringNeeded ?? '',
    notes: d.impl?.notes ?? '',
    findings: d.review?.findings ?? [],
  })),
  blockingFindings,
  // 並列化できず人手／直列が要るもの
  serial: serial.map((s) => ({ task: s.text, why: s.why, files: s.files })),
  blocked: blocked.map((b) => ({ task: b.text, why: b.blocked })),
  failedScout: failedScout.map((t) => t.text),
  integrationNotes: [
    `メインのワークツリーで ${BASE_BRANCH} に上記 mergeOrder の順で merge する。`,
    'wiringNeeded がある場合は、マージ後に統合役が TrinityForge.java へまとめて書く。',
    'マージ後に「1回だけ」全テストを走らせる（各レーンで走らせたテストは互いの変更を見ていない）。',
    'TF の public API を変更した場合は、メインのワークツリーで releaseAssembly を打ち直す（worktree ではフォークに届かない）。',
    'reports/ACTIVE_RECORD.md への追記は統合役が最後に1回だけ行う。',
  ],
}
