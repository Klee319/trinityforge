export const meta = {
  name: 'audit-drift',
  description: 'リポジトリ全体の「食い違い（drift）」を専門チェッカーで並列に洗い出す — ミラー不一致・yml未露出・配備鮮度・記録の腐り・ドキュメントの嘘',
  whenToUse:
    '大きなバッチの前後、配備の前、久しぶりに触るとき。個々のバグではなく「二重管理しているものがズレていないか」を見る。' +
    'コードは変更せず、ズレの一覧と重大度を返す。',
  phases: [
    { title: 'Check', detail: '観点ごとに独立したチェッカーを並列実行' },
    { title: 'Synthesize', detail: '重大度順に統合' },
  ],
}

// このリポジトリには「同じ事実を2か所以上に書いている」箇所が構造的に存在する:
//   Java の SchemaField ↔ 出荷 yml ↔ config-editor の lib/ ↔ config-editor の public/js/
//   ソース ↔ ビルド成果物 ↔ 配備済み jar
//   実コード ↔ reports/ACTIVE_RECORD.md ↔ docs/agent-context/
// drift は必ずここで起きる。しかもどれも「無言で」ズレる（テストも起動ログも何も言わない）。
// なので観点ごとに専門のチェッカーを立て、片方を真とせず**両方を読ませて突き合わせる**。

const FINDINGS_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['findings'],
  properties: {
    checkedWhat: { type: 'string', description: '実際に突き合わせた対象（ファイル・コマンド）' },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['title', 'detail', 'severity'],
        properties: {
          title: { type: 'string', description: '一行でズレを言い切る' },
          detail: { type: 'string', description: '何と何が食い違っているか。具体的な値・パス・行番号' },
          severity: {
            type: 'string',
            enum: ['critical', 'high', 'medium', 'low'],
            description:
              'critical=実サーバが壊れている/壊れる, high=機能が無言で効かない, medium=保守性, low=表記',
          },
          fix: { type: 'string', description: '直し方（どちらを真とするか）' },
        },
      },
    },
  },
}

const CHECKS = [
  {
    key: 'editor-mirror',
    prompt: `**config-editor の二重管理ミラーがズレていないか**を確認してください。

\`tools/config-editor/lib/\` （サーバ側）と \`tools/config-editor/public/js/\` （クライアント側）には
**同じ定義のミラーが2本**あります。片方だけ直すと**無言で食い違い**、「保存できるのに画面に出ない」
「画面に出るのに保存されない」になります。特に \`constants.js\`。

やること:
1. 両ディレクトリの同名ファイルを列挙し、定義（フィールド一覧・キー名・バリデーション範囲）を突き合わせる。
2. **片側にしか存在しないキー**を全部挙げる。
3. \`tools/config-editor/test/\` に往復テストがあるキーとないキーを区別する。

先に \`docs/agent-context/config-editor.md\` を読むこと。コードは変更しないこと。`,
  },
  {
    key: 'yml-schema-coverage',
    prompt: `**出荷 yml のキーが Java 側と config-editor 側の両方に露出しているか**を確認してください。

このリポジトリでは 1 つの設定キーが 3 か所に現れます:
- \`TrinityForge/src/main/resources/**/*.yml\`（**出荷 yml が真源**）
- \`TrinityForge/src/main/java/com/trinityforge/config/domains/*.java\` の \`SchemaField\`
- \`tools/config-editor\` の \`FIELD_SPECS\`

やること:
1. \`config/domains/*.java\` の \`SchemaField\` 定義を集める。
2. 対応する出荷 yml のキーと突き合わせ、**yml にあるのに SchemaField に無いキー**（＝Java が読まない死に設定）と
   **SchemaField にあるのに yml に無いキー**（＝既定値だけで動いている）を挙げる。
3. さらに **editor から設定できないキー**を挙げる（実装済みなのに GUI から触れない＝運用上の穴）。

網羅は難しいので、**確実に言えるものだけ**挙げてください。数を稼ぐために推測を混ぜないこと。
先に \`docs/agent-context/config-editor.md\` を読むこと。コードは変更しないこと。`,
  },
  {
    key: 'artifact-freshness',
    prompt: `**ビルド成果物がソースより新しいか（配備鮮度）**を確認してください。

このコードベースで「直したのに症状が消えない」の大半は**ビルド漏れか配備漏れ**です。

やること:
1. \`TrinityForge/build/\` 配下の jar（特に \`build/release/\`）のタイムスタンプとサイズを取る。
2. \`TrinityForge/src/\` 配下で**その jar より新しいファイル**を列挙する（あれば jar は stale）。
3. \`fork-handoff/\` 配下に jar があれば同じことをする。**fork のソースは \`.gitignore\` で除外されている**ので
   存在しないこともある。その場合は「存在しない」と報告する（エラーにしない）。
4. \`reports/ACTIVE_RECORD.md\` に書かれている「配備済み／未配備」の記述と実物の時刻を突き合わせる。

Bash で \`ls -la --time-style=full-iso\` 等を使って**実測**すること。推定で書かないこと。
コードは変更しないこと。配備（\`D:/\` への書き込み）は絶対に行わないこと。`,
  },
  {
    key: 'active-record-rot',
    prompt: `**\`reports/ACTIVE_RECORD.md\` の記述が実コードと合っているか**を確認してください。

この文書は残タスクの唯一の一次情報ですが、**並行セッション運用なのですぐ腐ります**
（過去の棚卸しでは「判断待ち」として繰り越されていた項目の 15 件が既に実装済みでした）。

やること:
1. \`ACTIVE_RECORD.md\` の**開いている項目**（取り消し線が付いていないもの）を列挙する。
2. そのうち **実コードを読めば既に実装済みと判定できるもの**を特定する（根拠の file:line を付ける）。
3. 逆に「解決済み」と書かれているのに**コードにその修正が見当たらないもの**を特定する。
4. 「実装済みで config 未設定なだけ」のものは**残タスクではない**ので、そう明記する。

**必ず実コードで裏を取ること。** 文面だけで判断しない。コードは変更しないこと。`,
  },
  {
    key: 'agent-context-lies',
    prompt: `**\`docs/agent-context/*.md\` に書かれているコードの位置が今も実在するか**を確認してください。

この文書群はエージェントが着手前に必ず読む前提の知識ベースなので、
**嘘が書いてあると全員が同じ間違いをします**（特にクラス名・メソッド名・yml キー）。

やること:
1. \`docs/agent-context/\` の全ファイルを読み、言及されている
   クラス名・メソッド名・ファイルパス・yml キーを抽出する。
2. それぞれが**実在するか** grep / Glob で確認する。
3. **実在しないもの**（改名・削除された、あるいは最初から誤記）を挙げる。
4. 相互リンク（\`./combat.md\` 等）が切れていないかも確認する。

存在しないものだけを findings にしてください（存在するものを列挙する必要はありません）。
コードは変更しないこと。`,
  },
  {
    key: 'silent-test-skips',
    prompt: `**テストが「黙って素通り」していないか**を確認してください。

このコードベースには **MockBukkit が未実装 API を呼ぶとテストが失敗ではなく SKIPPED に化ける**罠があり、
「全緑だから安全」が成り立ちません。

やること:
1. \`docs/agent-context/common-traps.md\` の該当項目を読む。
2. テストを実走する:
   \`\`\`bash
   cd TrinityForge && ./gradlew test --offline "-Dorg.gradle.java.home=C:\\Program Files\\Java\\jdk-21"
   \`\`\`
   （**JDK のパスを必ず渡すこと。** 渡さないと Gradle が意味不明なバージョンエラーで落ちます）
3. テスト結果 XML の \`skipped=\` を**直接数えて**、スキップされているテストを全部列挙する。
4. 各スキップが「正当な既知の 2 件」なのか、**未実装 API で無言に落ちたもの**なのかを判定する。
5. 失敗しているテストがあれば、それが**自分の変更由来でない**（他セッションの未コミット変更由来の）
   ものかどうかも切り分けて報告する。

実出力を根拠として引用すること。テストコードは変更しないこと。`,
  },
  {
    key: 'git-hygiene',
    prompt: `**リポジトリの git 衛生**を確認してください。**変更・commit・push は一切しないこと（読み取り専用）。**

やること:
1. \`git status\` で未コミットの変更を列挙する。**このワークツリーは複数セッションが並行で使う**ので、
   自分のものでない WIP が混ざっているのが正常です。「消す」提案はしないこと。
2. **public リポジトリに入ってはいけないもの**が追跡対象／ステージに無いかを確認する:
   jar、サーバの実設定値、秘密、巨大バイナリ。\`git ls-files\` で \`*.jar\` を検索する。
3. \`.gitignore\` / \`.gitattributes\` が存在し、fork のソース除外（\`fork-handoff/*/fork\` 系）と
   \`text eol=lf\` が生きているかを確認する。
4. \`tmp/\`, \`backups/\`, ログファイル等が追跡対象に漏れていないか確認する。

先に \`docs/agent-context/ops-build-deploy.md\` を読むこと。`,
  },
]

phase('Check')
const results = await parallel(
  CHECKS.map((check) => () =>
    agent(
      `あなたは TrinityForge（Minecraft Paper 1.21.11 / Java 21 のプラグイン）の**整合性監査**担当です。
以下の観点だけを担当します。**他の観点には手を出さないでください**（別のエージェントが並列で見ています）。

${check.prompt}

## 共通の禁止事項
- ファイルの変更、git の変更操作、\`D:/\` への書き込み。すべて**読み取り専用**です。
- **推測を findings に混ぜないこと。** 確認できたことだけを書き、確認できなかった範囲は checkedWhat に明記する。
- ズレが 1 件も無ければ findings を空配列で返す。**無理に絞り出さないこと。**`,
      { label: `check:${check.key}`, phase: 'Check', schema: FINDINGS_SCHEMA }
    ).then((r) => ({ key: check.key, ...(r ?? { findings: [] }) }))
  )
)

const ok = results.filter(Boolean)
const all = ok.flatMap((r) => (r.findings ?? []).map((f) => ({ ...f, check: r.key })))
const rank = { critical: 0, high: 1, medium: 2, low: 3 }
all.sort((a, b) => (rank[a.severity] ?? 9) - (rank[b.severity] ?? 9))

log(`チェッカー ${ok.length}/${CHECKS.length} 完了 / drift ${all.length} 件（critical ${all.filter((f) => f.severity === 'critical').length}）`)

phase('Synthesize')
return {
  findings: all,
  bySeverity: {
    critical: all.filter((f) => f.severity === 'critical'),
    high: all.filter((f) => f.severity === 'high'),
    medium: all.filter((f) => f.severity === 'medium'),
    low: all.filter((f) => f.severity === 'low'),
  },
  checkedWhat: ok.map((r) => ({ check: r.key, scope: r.checkedWhat ?? '' })),
  failedChecks: CHECKS.filter((c) => !ok.some((r) => r.key === c.key)).map((c) => c.key),
}
