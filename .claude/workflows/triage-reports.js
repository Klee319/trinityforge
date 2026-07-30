export const meta = {
  name: 'triage-reports',
  description: '実サーバのバグ報告を1件ずつ担当ドメインへ振り分け、根本原因を機構レベルで特定して反証まで通す',
  whenToUse:
    'ユーザーから複数のバグ報告・要望がまとめて来たとき。args に報告の配列（または 1 本の生テキスト）を渡す。' +
    'コードは変更せず、原因・担当ドメイン・修正方針・確度を返す。',
  phases: [
    { title: 'Split', detail: '報告を1件ずつに割り、担当ドメインを判定' },
    { title: 'Diagnose', detail: '報告ごとに実コードを読んで根本原因を特定' },
    { title: 'Refute', detail: '各診断を反証しに行く（推測を落とす）' },
    { title: 'Synthesize', detail: 'トリアージ表にまとめる' },
  ],
}

// このワークフローは「原因を当てる」ことだけを目的にしている。修正はしない。
// 理由: このコードベースの不具合は「設定ミス」に見えて実際は配備漏れ・ビルド漏れ・
// イベント発火順・スレッド跨ぎであることが多く、原因を外したまま実装に入ると丸ごと無駄になる。

const MAX_REPORTS = 6 // 1報告あたり最大2エージェント使うので、全体を15前後に収めるための上限

const DOMAIN_DOCS = {
  combat: 'docs/agent-context/combat.md',
  progression: 'docs/agent-context/progression-skilltree.md',
  editor: 'docs/agent-context/config-editor.md',
  forks: 'docs/agent-context/forks-and-mobs.md',
  ops: 'docs/agent-context/ops-build-deploy.md',
  bedrock: 'docs/agent-context/bedrock-geyser.md',
}

const SPLIT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['reports'],
  properties: {
    reports: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['text', 'domain'],
        properties: {
          text: { type: 'string', description: '報告1件の原文（要約せず、そのまま）' },
          domain: {
            type: 'string',
            enum: ['combat', 'progression', 'editor', 'forks', 'ops', 'bedrock'],
          },
          why: { type: 'string', description: 'そのドメインに振った理由（1文）' },
        },
      },
    },
  },
}

const DIAGNOSIS_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['cause', 'mechanism', 'evidence', 'fixPlan', 'confidence'],
  properties: {
    cause: { type: 'string', description: '一行の原因' },
    mechanism: { type: 'string', description: 'なぜそうなるか。イベント発火順・スレッド・API の挙動レベルで' },
    evidence: {
      type: 'array',
      items: { type: 'string' },
      description: '根拠にした file:line。実際に読んだものだけ',
    },
    alreadyFixed: {
      type: 'boolean',
      description: 'コードは既に直っており、配備漏れ／ビルド漏れが真因である場合 true',
    },
    fixPlan: { type: 'string', description: '修正方針。触るファイルを列挙する' },
    confidence: { type: 'string', enum: ['high', 'medium', 'low'] },
  },
}

const REFUTE_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['refuted', 'reason'],
  properties: {
    refuted: { type: 'boolean' },
    reason: { type: 'string' },
    betterCause: { type: 'string', description: '別の原因が見つかった場合のみ' },
  },
}

const raw = args
const rawText = typeof raw === 'string' ? raw : Array.isArray(raw) ? raw.join('\n') : String(raw ?? '')

if (!rawText.trim()) {
  log('args にバグ報告のテキスト（または配列）を渡してください。')
  return { reports: [], note: 'no input' }
}

phase('Split')
const split = await agent(
  `以下は Minecraft プラグイン TrinityForge の実サーバから上がってきたバグ報告・要望の生テキストです。
これを「1件ずつ」に割り、それぞれ担当ドメインを判定してください。

## 生テキスト
${rawText}

## 担当ドメインの定義
- combat: ダメージ、守備力、会心、マナ、属性、状態異常、モブのHP/攻撃力、PvP、装備耐久
- progression: スキルツリー、EXP、パーク、アチーブメント、採取ギミック、レシピ／カタログ、クラフト
- editor: tools/config-editor（GUI 設定エディタ）の挙動
- forks: EliteMobs / ArsPaper フォーク、ダンジョン、魔法
- ops: ビルド、配備、起動失敗、ログの例外、権限、プロキシ
- bedrock: 統合版（Bedrock / Geyser）固有の表示・操作

## 判定のコツ（このリポジトリ特有）
- 「起動後から急に全部おかしい」「大量の例外」→ ops（稼働中の jar 差し替え事故の可能性）
- 「GUI のノードがずれる/消える/くっつく」→ progression（座標は yml に無く jar が生成する）
- 「レシピ帳に出るのに作れない」→ progression か forks（カスタム素材の登録漏れ）
- 「設定したのに効かない」→ まず ops（配備漏れ）を疑う

原文は要約せずそのまま text に入れてください。`,
  { label: 'split', phase: 'Split', schema: SPLIT_SCHEMA }
)

let reports = (split?.reports ?? []).filter((r) => r && r.text)
if (reports.length > MAX_REPORTS) {
  log(`報告 ${reports.length} 件のうち先頭 ${MAX_REPORTS} 件のみ診断します（残り ${reports.length - MAX_REPORTS} 件は未診断）。`)
  reports = reports.slice(0, MAX_REPORTS)
}
log(`${reports.length} 件を診断します: ${reports.map((r) => r.domain).join(', ')}`)

const results = await pipeline(
  reports,
  (report) =>
    agent(
      `TrinityForge（Paper 1.21.11 / Java 21）の実サーバから上がった報告 1 件の**根本原因を特定**してください。
**コードは変更しないこと。** これは診断専用のタスクです。

## 報告
${report.text}

## 着手前に必ず読むもの（全文）
1. \`${DOMAIN_DOCS[report.domain] ?? DOMAIN_DOCS.ops}\` — この領域の恒久知識。既知の落とし穴が並んでいます
2. \`docs/agent-context/common-traps.md\` — API とテストの共通罠
3. \`reports/ACTIVE_RECORD.md\` の該当箇所 — **既知の項目かどうか**、既に修正済みで未配備なだけかどうか

## 診断の作法
- **必ず実コードを読む。** grep の結果だけで判断しない。イベントの発火順・優先度・スレッドを確認する。
- **「たぶんここ」で止めない。** なぜその症状になるのかを機構レベルで説明できるまで掘る。
- **「コードは直っているが配備されていない」を必ず候補に入れる**（このコードベースで頻発する）。
  その疑いがあるなら生成物のタイムスタンプを確認し、\`alreadyFixed\` を true にする。
- 根拠は実際に読んだ file:line だけを挙げる。**読んでいない場所を根拠に書かない。**
- 分からなければ confidence を low にする。**推測で high を付けない。**`,
      { label: `diagnose:${report.domain}`, phase: 'Diagnose', schema: DIAGNOSIS_SCHEMA }
    ).then((d) => ({ report, diagnosis: d })),
  (prev) => {
    if (!prev?.diagnosis) return prev
    return agent(
      `次の診断を**反証**してください。あなたの仕事は「正しいと確認する」ことではなく **「間違いを見つける」** ことです。

## 報告
${prev.report.text}

## 診断（これを疑う）
- 原因: ${prev.diagnosis.cause}
- 機構: ${prev.diagnosis.mechanism}
- 根拠: ${(prev.diagnosis.evidence ?? []).join(' / ')}
- 配備漏れが真因との主張: ${prev.diagnosis.alreadyFixed ? 'あり' : 'なし'}

## やること
1. 挙げられた file:line を**実際に開いて**、書かれている機構が本当にそうなっているか確認する。
2. 「その原因なら、報告されていない別の症状も出るはず」を考え、それが出ていないなら診断を疑う。
3. 同じ症状を出しうる**別の経路**を探す（別リスナー、fork 側、優先度、キャンセル、スレッド）。
4. 根拠に挙がっている場所が存在しない／内容が違うなら、それだけで refuted。

判断に迷う場合は refuted = true 側に倒してください（誤った診断で実装に入るコストの方が大きい）。`,
      { label: `refute:${prev.report.domain}`, phase: 'Refute', schema: REFUTE_SCHEMA }
    ).then((v) => ({ ...prev, refutation: v }))
  }
)

const triaged = results.filter(Boolean)

phase('Synthesize')
const table = triaged.map((t) => ({
  report: t.report.text,
  domain: t.report.domain,
  cause: t.diagnosis?.cause ?? '(診断失敗)',
  mechanism: t.diagnosis?.mechanism ?? '',
  evidence: t.diagnosis?.evidence ?? [],
  alreadyFixed: t.diagnosis?.alreadyFixed ?? false,
  fixPlan: t.diagnosis?.fixPlan ?? '',
  confidence: t.diagnosis?.confidence ?? 'low',
  survivedRefutation: t.refutation ? !t.refutation.refuted : null,
  refutationReason: t.refutation?.reason ?? '',
  betterCause: t.refutation?.betterCause ?? '',
}))

const shaky = table.filter((t) => t.survivedRefutation === false || t.confidence === 'low')
log(`診断 ${table.length} 件 / 反証で崩れた・確度低 ${shaky.length} 件`)

return {
  triage: table,
  needsMoreInvestigation: shaky.map((t) => t.report),
  suggestedAgents: table.map((t) => ({
    report: t.report,
    agent: {
      combat: 'tf-combat',
      progression: 'tf-progression',
      editor: 'tf-editor',
      forks: 'fork-elitemobs',
      ops: 'tf-ops',
      bedrock: 'tf-ops',
    }[t.domain],
  })),
}
