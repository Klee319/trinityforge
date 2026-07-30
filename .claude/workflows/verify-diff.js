export const meta = {
  name: 'verify-diff',
  description: '未コミットの差分（または指定コミット範囲）をドメイン別にレビューし、各指摘を反証して確定分だけ返す',
  whenToUse:
    '実装が終わって commit / 配備する直前。並行セッションの WIP が混ざるワークツリーなので、' +
    'args に自分が触ったパスの配列を渡すと自分の変更だけをレビューできる。args を省略すると全未コミット差分を見る。',
  phases: [
    { title: 'Scope', detail: '差分を取り、ドメインごとに振り分ける' },
    { title: 'Review', detail: 'ドメインごとに恒久知識を読ませてレビュー' },
    { title: 'Refute', detail: '指摘を1件ずつ反証（誤検知を落とす）' },
  ],
}

// 前提: このワークツリーは複数セッションが並行で使う。よって `git diff` には
// 他人の WIP が混ざる。args に自分の触ったパスを渡すのが正しい使い方。
// レビューの肝は「恒久知識を読ませてから見せる」こと。docs/agent-context/ を読まないレビューは
// 一般論しか出ず、このコードベース特有の「無言で壊れる」パターンを見逃す。

const MAX_FINDINGS_PER_DOMAIN = 4 // 反証エージェントが増えすぎないための上限

const DOMAINS = {
  combat: {
    doc: 'docs/agent-context/combat.md',
    lens: 'ダメージ式の順序（守備力は初回減算・会心の前／固定ダメージは全貫通）、確定値の再スケール、二重加算、スレッド安全性、致死ダメージでの付与',
  },
  progression: {
    doc: 'docs/agent-context/progression-skilltree.md',
    lens: 'スキルツリーのレイアウト3規則、排他グループの親子関係、EXP の付与経路（一点集約）、クラフト成立判定、既存の拘束テストを弱めていないか',
  },
  editor: {
    doc: 'docs/agent-context/config-editor.md',
    lens: 'lib/ と public/js/ のミラー両方に入っているか、ロスレス保存（触っていないキーの温存）、生成物の手編集、WeakMap の同一性',
  },
  forks: {
    doc: 'docs/agent-context/forks-and-mobs.md',
    lens: 'TF 側と fork 側の役割分担、ダンジョンでのイベント非発火、モブ id の正規化、API 変更に伴う compileOnly jar の再生成',
  },
  ops: {
    doc: 'docs/agent-context/ops-build-deploy.md',
    lens: '配備手順の安全性（停止→配備→起動）、.cmd の ASCII 制約、git add の範囲、public リポジトリに入れてはいけないもの',
  },
  bedrock: {
    doc: 'docs/agent-context/bedrock-geyser.md',
    lens: '統合版で解釈できない実装（属性による採掘速度等）、パック側の登録漏れ、命名の総取り',
  },
}

const SCOPE_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['groups'],
  properties: {
    groups: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['domain', 'files'],
        properties: {
          domain: {
            type: 'string',
            enum: ['combat', 'progression', 'editor', 'forks', 'ops', 'bedrock'],
          },
          files: { type: 'array', items: { type: 'string' } },
        },
      },
    },
    excluded: {
      type: 'array',
      items: { type: 'string' },
      description: 'レビュー対象外にしたパスとその理由',
    },
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
        required: ['title', 'file', 'detail', 'severity'],
        properties: {
          title: { type: 'string' },
          file: { type: 'string', description: 'file:line' },
          detail: { type: 'string', description: '何が壊れるか。具体的な入力・状態→結果で書く' },
          severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low'] },
          fix: { type: 'string' },
        },
      },
    },
  },
}

const REFUTE_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['refuted', 'reason'],
  properties: {
    refuted: { type: 'boolean' },
    reason: { type: 'string' },
  },
}

const targets = Array.isArray(args) ? args : args ? [String(args)] : []
const scopeHint = targets.length
  ? `レビュー対象は以下のパスに限定してください（他は並行セッションの変更なので触らない）:\n${targets.map((t) => `- ${t}`).join('\n')}`
  : '未コミットの全差分を対象にしてください。ただし**明らかに別作業の WIP** と分かるものは excluded に回してください。'

phase('Scope')
const scope = await agent(
  `TrinityForge（Minecraft Paper 1.21.11 / Java 21）の作業ツリーの差分を取り、ドメインごとに振り分けてください。

${scopeHint}

## やること
1. \`git status --porcelain\` と \`git diff --stat\`（必要なら \`git diff --cached --stat\`）で変更ファイルを列挙する。
2. 各ファイルを次のドメインに振り分ける:
   - combat: \`com/trinityforge/combat/**\`, \`durability/**\`, 戦闘系 listener, \`resources/combat/*.yml\`, \`resources/stats/*.yml\`
   - progression: \`skilltree/**\`, \`progression/**\`, \`gathering/**\`, \`catalog/**\`, EXP/採取/クラフト系 listener, \`resources/skilltree/**\`, \`resources/skills/**\`
   - editor: \`tools/config-editor/**\`
   - forks: \`fork-handoff/**\`, TF 側の fork 連携 API
   - ops: \`ops/**\`, \`tmp/*.cmd\`, ビルドスクリプト, \`reports/**\`, \`docs/**\`, \`.claude/**\`
   - bedrock: \`resourcepack/**\`, Geyser/Bedrock 関連
3. **1 ファイルは 1 ドメインだけ**に入れる（迷ったら影響が大きい方）。
4. 変更が無ければ groups を空配列で返す。

**ファイルの変更・git の変更操作はしないこと（読み取り専用）。**`,
  { label: 'scope', phase: 'Scope', schema: SCOPE_SCHEMA }
)

const groups = (scope?.groups ?? []).filter((g) => g && (g.files ?? []).length)
if (!groups.length) {
  log('レビュー対象の差分がありません。')
  return { findings: [], note: 'no diff' }
}
log(`${groups.length} ドメイン / ${groups.reduce((n, g) => n + g.files.length, 0)} ファイルをレビューします`)
if (scope?.excluded?.length) log(`対象外: ${scope.excluded.join(', ')}`)

const reviewed = await pipeline(
  groups,
  (group) => {
    const spec = DOMAINS[group.domain] ?? DOMAINS.ops
    return agent(
      `TrinityForge の差分を**レビュー**してください。**コードは変更しないこと。**

## 対象ファイル（${group.domain}）
${group.files.map((f) => `- ${f}`).join('\n')}

## 着手前に必ず読むもの（全文）
1. \`${spec.doc}\` — この領域の恒久知識。**ここに書かれている落とし穴を差分が踏んでいないか**が最重要の観点
2. \`docs/agent-context/common-traps.md\` — API とテストの共通罠

## 見る観点
${spec.lens}

## レビューの作法
- \`git diff -- <path>\` で差分を読み、**周辺の既存コードも開く**（差分だけでは順序・優先度・呼び出し元が分からない）。
- 指摘は「どういう入力・状態で何が起きるか」まで具体的に書く。**一般論（命名が〜、責務が〜）は書かない。**
- **テストが「通っている」を根拠にしないこと。** MockBukkit は未実装 API を SKIPPED に化けさせるので、
  新しいテストが本当に走っているかを確認する。
- yml キーを足しているなら **Java 側の SchemaField と config-editor のミラー 2 本**にも入っているか確認する。
- 既存テストの期待値を書き換えている差分があれば、**それが正当な仕様変更か、通すための改変か**を判定する。
- 問題が無ければ findings を空配列で返す。**数を稼ぐために薄い指摘を並べないこと。**`,
      { label: `review:${group.domain}`, phase: 'Review', schema: REVIEW_SCHEMA }
    ).then((r) => ({ domain: group.domain, findings: (r?.findings ?? []).slice(0, MAX_FINDINGS_PER_DOMAIN), dropped: Math.max(0, (r?.findings ?? []).length - MAX_FINDINGS_PER_DOMAIN) }))
  },
  (prev) => {
    if (!prev) return prev
    if (prev.dropped) log(`${prev.domain}: 指摘 ${prev.dropped} 件を上限超過で未検証のまま省きました`)
    if (!prev.findings.length) return { ...prev, verified: [] }
    return parallel(
      prev.findings.map((f) => () =>
        agent(
          `次のレビュー指摘を**反証**してください。あなたの仕事は「正しいと確認する」ことではなく **「誤検知を落とす」** ことです。

## 指摘
- 見出し: ${f.title}
- 場所: ${f.file}
- 内容: ${f.detail}
- 重大度: ${f.severity}

## やること
1. 指摘された場所を**実際に開く**。行が存在しない／内容が違うなら、それだけで refuted。
2. 「その通りなら再現する具体的な操作」を考え、**既存のガード・上流のクランプ・呼び出し元の条件**で
   実際には起きないのではないかを確認する。
3. 既存テストがその経路を既に固定していないか確認する。
4. 恒久知識（\`docs/agent-context/\`）に「それは意図的にそうしている」と書かれていないか確認する。

**確認できない場合は refuted = true 側に倒してください**（誤検知を残すと本物の指摘が埋もれます）。
ファイルの変更はしないこと。`,
          { label: `refute:${prev.domain}`, phase: 'Refute', schema: REFUTE_SCHEMA }
        ).then((v) => ({ ...f, domain: prev.domain, verdict: v }))
      )
    )
  }
)

const all = reviewed.filter(Boolean).flat().filter(Boolean)
const confirmed = all.filter((f) => f.verdict && !f.verdict.refuted)
const rejected = all.filter((f) => !f.verdict || f.verdict.refuted)
const rank = { critical: 0, high: 1, medium: 2, low: 3 }
confirmed.sort((a, b) => (rank[a.severity] ?? 9) - (rank[b.severity] ?? 9))

log(`指摘 ${all.length} 件 → 反証を生き延びた ${confirmed.length} 件 / 却下 ${rejected.length} 件`)

return {
  confirmed,
  rejected: rejected.map((f) => ({ title: f.title, file: f.file, reason: f.verdict?.reason ?? '(反証エージェント失敗)' })),
  blocking: confirmed.filter((f) => f.severity === 'critical' || f.severity === 'high'),
}
