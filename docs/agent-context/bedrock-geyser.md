# 統合版（Bedrock / Geyser）の恒久知識

Geyser経由でBedrock（統合版）プレイヤーを受け入れているサーバー特有の、Java版だけを見ていると
気づけない恒久的な制約・変換原理をまとめる。作業履歴・完了報告は含めない。
統合版対応（採掘速度、カスタムアイテム、リソースパック変換）に触る前に必ず目を通すこと。

## サーバー機能の実装可否

### ⚠️ 採掘速度をバニラ属性で実装してはいけない（Geyserが解釈できない）
`Attribute.MINING_EFFICIENCY` / `Attribute.BLOCK_BREAK_SPEED` はBedrock側に存在しない属性で、
Geyserが統合版クライアントへ送る採掘時間の自前計算にも含まれない。実装すると
サーバー判定とクライアント表示がずれ、「素手速度に戻ってはまた通常に戻る」「ゴーストブロック」に
なる（GeyserMC/Geyser#6266・#3113で報告済みの既知不具合）。
**「バニラの機構だから安全」という判断は誤り** — バニラ機構であることとGeyserが翻訳できることは別問題。
→ 唯一の互換手段は**効率強化(efficiency)エンチャントのレベル操作**。Geyserの採掘時間計算に
効率強化は含まれているため統合版でも正しく効く。ただし**効率強化6以上は既知不具合
（#5843: 統合版が連打で異常に速く掘れる）**を踏むため上限は5を既定にすること。
実行時にレベルを加算する実装は「アイテムを実際に書き換える」ため、付与量のPDC記録・
インベントリを開いた瞬間の剥がし（金床への焼き付き防止）・ログイン時の復旧走査が必須になる。
なお、クラフト時にツールへ効率強化を刻む既存の `tool-enchant-efficiency` は統合版でも
正常動作するため、これはそのまま残してよい。

## コマンド名の衝突

### ⚠️ 非修飾コマンド名は先に enable したプラグインが総取りする
Bukkitの非修飾コマンド名（例: `/menu`）は先に enable したプラグインが登録を取り、後から
登録した側は `plugin:command`（例: `/geyserextra:menu`）でしか呼べなくなる。
GeyserExtraは `loadbefore: [Geyser-Spigot]` の都合で早くenableするため、他プラグインの
`/menu` `/ga` のようなコマンドを奪っていた実例がある。症状は「特定のコマンドだけ効かない、
または別プラグインの機能が出てくる」で、ログに `/<奪った側>:<command>` が残るのが手がかり。
**「特定コマンドだけ効かない」報告を見たら、まず `plugin.yml`/`paper-plugin.yml` の
commands節を全プラグイン横断で突き合わせること。**
汎用的な動詞コマンドはサーバー運営者向けに空けておくのが望ましい。現状まだ汎用名のまま
残っているもの（新規プラグイン導入時に同種の事故が起き得る）: `offhand`/`oh`, `tooltip`/`tt`,
`advancements`/`adv`, `stats`, `gamerules`/`gr`, `settings`/`ds`。

## カスタムアイテムの統合版登録

### ⚠️ `item_model` データコンポーネント方式は実行時discovery頼みで、パックを読むだけでは統合版に出ない
`minecraft:item_model` でモデルを差し替えたアイテム（TFのスキルツリーGUIなど）は
`assets/<ns>/items/*.json` が「どのバニラアイテムに乗るか」の情報を持たないため、
パックを生成するだけではGeyser側に登録できない。GeyserExtra拡張は実行時に実際のItemStackを
観測して `(baseItem, item_model)` を拾う経路しか持たず、これは**GUIを開いた人がいて、
かつその後サーバーを再起動するまで反映されない**。ログの
`[ItemModel] Discovered ... will activate after restart` が「観測はしたが未反映」のサイン。
→ 正解は `<Geyser>/extensions/geyserextra/item_model_hints/*.json` に
`{"entries":[{"item_model":"ns:path","base_item":"minecraft:xxx"}]}` を事前に置くこと。
起動時のパック生成前にpre-registerされるため初回から乗る。TF側の生成器は
`resourcepack/build_item_model_hints.py`（ベースアイテムの出どころは `SkillTreeGuiVisuals` と
各 `skilltree/*.yml` の `icon:`）。
デバッグ手順: 「統合版だけテクスチャが出ない」場合、まず `custom_items.json` に該当
`item_model` があるか、次に生成済みパックの `textures/items/` に出ているかを見る。
前者だけあって後者に無ければdiscoveryとパック生成の順序問題。

### ⚠️ `custom_items.json` は再生成できない永続台帳。消すと再観測するまで戻らない
`<Geyser>/extensions/geyserextra/custom_items.json` は生成物ではなく永続レジストリで、
PDC経路のエントリは**liveなItemStackを観測したときにしか作られない**。一度消すと
「そのアイテムを誰かが再び手に持つ/触る」まで戻らない。バックアップ機構も無い
（登録処理は上書き保存のみ）。**このファイルを消す/絞る変更は事実上のデータ削除として扱うこと。**
反映は必ず2段階: (1) Paperが観測して `custom_items.json` に書く → (2) **プロキシを再起動**
して拡張がファイルを読み直しGeyserに登録する。Paperだけ再起動しても登録数は変わらない。
ログの `Registered NNN custom items` が期待値と違うときは、この2段階のどちらで止まっているかを見る。

#### 例外: 「毎起動パック走査から再導出できる」エントリだけは外科的に消してよい
台帳のエントリは由来が 2 系統ある。**片方は消しても次の起動で必ず戻る**ので、
「台帳＝全部消してはいけない」を「絞り込みも一切できない」と読むと詰む
（下記「既存エントリは lang を足しても直らない」を直す唯一の手段がこれ）。

| 由来 | 見分け方 | 消したらどうなるか |
|---|---|---|
| パック先行登録 (`prepopulateRegistryFromJavaPack`) | `name` が `custom_<ベースアイテム>_<cmd>` ちょうど／`cmd > 0`／`display_name` が `prettifyBaseItemName(baseItem)` の英語整形そのもの (`Wooden Sword`)／キーが `name` `custom_model_data` `display_name` `creative_category` `register` `allow_offhand` だけ | **次の Paper 起動で作り直される**（ただしそのCMDが今のパックに在ることが条件） |
| 実物観測 (`CustomItemScanner`) | `pdc_identifier` / `armor` / `item_model` / `unbreakable` が付く、または `display_name` が日本語 | **誰かが再びその実物を手に持つまで戻らない＝データ削除** |

**`name` の書式だけでは由来を切り分けられない。** `CustomItemScanner#generateMappingName` は
PDC の id が取れなかったとき **同じ `custom_<base>_<cmd>` を作る**（javap で確認済み）。
安全に消せる根拠は名前ではなく「**その `(ベースアイテム, cmd)` が今のパックに実在し、
かつ `trinityforge:` 名前空間の専用モデルを指している**」ことの側にある。
判定と削除は `ops/scripts/prune-geyser-auto-items.ps1`（実体は同名 `.py`）に実装済み。
既定 dry-run・バックアップ必須・PDC 由来を 1 件でも巻き込んだら中断する。
2026-08-02 の実測は **310 件中 94 件が削除対象／216 件は保全**（日本語名を持つ 120 件は全部残る）。
**Paper 稼働中に実行してはいけない**（GeyserExtra が同じファイルを上書き保存で書き戻す）。

### ⚠️ カスタムアイテム名はパックの `texts/*.lang` からしか出ない
Geyserのカスタムアイテムは新規のBedrockアイテム `geyserextra:<name>` として登録され、
Bedrockクライアントはその名前を**パックの `texts/*.lang` から引く**しか手段がない。
エントリが無いと識別子そのものが名前として表示される。サーバー側の `custom_items.json` に
`display_name` が正しく入っていても無関係（データは正しいのにパック側の `texts/` が
無いだけ、というケースがある。生成パックを実際に展開して確認するのが決め手で、
JSONを眺めるだけでは分からない）。
- キーの形は `item.geyserextra:<name>` と `item.geyserextra:<name>.name` の両方を書く
  （Bedrockのバージョンで揺れがあるため）。実測（2026-08-02、配備中の
  `packs/geyserextra_auto.zip`）でも両形式が出力されている。
- 識別子のサニタイズ規則は両側（パック生成側とハンドラ側）で一致させる必要がある
  （`[^a-z0-9_\-./]` → `_` のような変換が片方だけずれると、lang キーが不一致になり
  無言でID表示に戻る）。
- **この `texts/*.lang` を書くのは TF ではなく GeyserExtra** で、中身は
  `custom_items.json` の `display_name` の写しでしかない。だから実務上の作業対象は
  lang ファイルではなく **台帳の `display_name`** になる（次節）。

### ⚠️ `texts/*.lang` を書くのは GeyserExtra 側。TFリポジトリが用意するのは「Javaパックの lang」
**`texts/*.lang` を TrinityForge-Pack.zip に入れても効かない。** あれは Java 版クライアントへ配る
Java パックであり、統合版クライアントが受け取るのは GeyserExtra が生成する
`<Geyser>/extensions/geyserextra/packs/geyserextra_auto.zip` の方だから。
GeyserExtra は `texts/{en_US,ja_JP}.lang` と `texts/languages.json` を既に生成しており、
その値は `custom_items.json` の `display_name` をそのまま流している。
**足りないのは lang ファイルではなく、そこへ流し込む「名前」の方だった。**

`display_name` の決まり方は 2 経路ある:
- **実行時スキャナ**（`CustomItemScanner#extractDisplayName`）: 実際の ItemStack の
  `ItemMeta.displayName()` → PDC の displayname 系キー → バニラ材質名。
  TF のカタログアイテムは表示名を持つので、**一度でも誰かが手に持てば**正しい名前になる。
- **パック先行登録**（`GeyserExtraPaper#prepopulateRegistryFromJavaPack` →
  `deriveFallbackDisplayName`）: 誰も触っていない CMD 用。モデル参照の終端名から
  Mojang 慣習の翻訳キー `item.<ns>.<終端名>` を組み立て、**オペレータの Java パックの
  `assets/<ns>/lang/<locale>.json`** を引く（`JavaPackLangReader`、primary は
  config の `javaPackLocale`＝`ja_jp`、フォールバックは `en_us`）。引けないと
  ベース材質名を英語整形した "Wooden Sword" になる。

TF のパックは lang を 1 枚も持っていなかったので常に後者の英語整形に落ちていた。
これが「木のツールだけ `wooden_*` のまま」の正体。→ `resourcepack/build_item_lang.py` が
`catalog.yml` / ArsPaper `materials.yml` の表示名（MiniMessage・`&`コードを剥がす）から
`assets/trinityforge/lang/{ja_jp,en_us}.json` を生成し、`build_item_pack.py` が毎回作り直す。

**やってはいけないこと**: lang のキーはパック全体でグローバルなので、`item.minecraft.*` を
書くと**バニラアイテムの名前を全 Java プレイヤー分書き換える**。専用モデルを持たず
`minecraft:item/diamond_sword` をそのまま指している CMD（2026-08-02 時点で 122 件）は
この方式では名前を付けられない — テクスチャを作って専用モデルに差し替えるのが先。
なお `resolveSingleModelRef` は「実在する PNG に解決できたモデル参照」しか採らないので、
専用モデルの無い CMD はそもそも Bedrock カスタムアイテムとして登録されず、
統合版ではバニラ名で出る（＝ID 表示にはならない）。

#### ⚠️ lang を足しただけでは **既存の登録は 1 件も直らない**（今回の一番の落とし穴）
`prepopulateRegistryFromJavaPack` は先頭で
`itemMappingRegistry.getByCustomModelData(baseItem, cmd).isPresent()` を見て、
**在ったらその CMD を丸ごと skip する**（`register()` にも `contains(name)` の二重ガードがある）。
レジストリは起動時に `custom_items.json` を読み込み済みなので、
**一度でも登録された CMD には二度と新しい `display_name` が入らない。**
実行時スキャナ側も救ってくれない — `CustomItemScanner` の更新分岐は
`hasDisplayName()` が false のときしか表示名を上げず、既存エントリは既に
`Wooden Sword` を持っているので条件を満たさない。

実測（2026-08-02、配備中の台帳）: パックの `trinityforge:` 専用モデル付き CMD は 113 件。
そのうち **105 件は既に台帳が埋まっていて lang が効かない**（94 件が英語フォールバック名、
11 件は実物観測で既に正しい日本語名）。lang だけで直るのは残る **8 件**しかない。
→ **`ops/scripts/prune-geyser-auto-items.ps1` で英語フォールバック名の 94 件を消してから
Paper を起動する**のが必須手順。上記「例外: 毎起動パック走査から再導出できるエントリ」を参照。

#### 反映は 4 段。1 つでも欠けると「直したのに何も変わらない」になる
1. **新しい `dist/TrinityForge-Pack.zip` を配布先（GitHub release）へ差し替える**
   （`resourcepack/build_item_pack.py` で再生成。**PyYAML が要る**: `python -m pip install pyyaml`。
   `build_item_lang.py` を同ディレクトリから import するので、リポジトリ内の相対配置を崩さないこと）
2. **`server.properties` の `resource-pack` と `resource-pack-sha1` を両方更新する**
   ⚠️ **sha1 を更新し忘れると何も変わらない。** `JavaPackResolver` は
   `<Paper>/plugins/GeyserExtra/cache/server-resource-pack.zip` と `.sha1` にパックをキャッシュし、
   `resource-pack-sha1` と一致していれば**ダウンロードそのものを省く**。
   URL だけ変えて sha1 を据え置くと、旧 zip をそのまま読み続ける
   （疑わしいときは `cache/server-resource-pack.sha1` の中身と server.properties を突き合わせる。
   キャッシュを消してしまうのも手）
3. **Paper を起動する** — ここで台帳が lang 由来の名前で埋め直される
4. **プロキシ（Velocity）を再起動する** — `packs/geyserextra_auto.pending.zip` が
   本番の `geyserextra_auto.zip` に入れ替わるのは拡張の起動時。
   `.pending.zip` が残っているのは「生成済みだが未適用」のサイン

### ⚠️ 「未登録にすれば直る」は誤り — 名前・手持ちポーズ・オフハンド可否は三者トレードオフではなく個別に直す
PDCだけで登録される（`customModelData=0`/`iconPath=null`）マッピングは描画を一切変えられないが、
それでも登録するとそのアイテムはバニラBedrockアイテムでなくなり、クライアントが無料で
提供していた2つを失う: (1) ローカライズ名（素材由来の英語推測名）、(2) 手持ちポーズ
（バニラのattachableが与える斜め持ち等）。
「登録をやめれば直る」と考えて未登録化すると、**統合版でオフハンドに置けなくなる**
（下記の通りオフハンド可否は「カスタムアイテムとして登録されたか」で決まるため）。
三者は個別の手段で直すのが正解:
- オフハンド可否 → 登録し続ける（`allow_offhand`）
- 手持ちポーズ → `CustomItemBedrockOptions.displayHandheld(true)` を明示する
  （Bedrockは `hand_equipped` で斜め持ちを決め、カスタムアイテムはバニラのそれを継承しない。
  ベースアイテムの接尾辞 `_sword`/`_pickaxe`等から判定する）
- 名前 → 手段は確定（Java パック側に `assets/trinityforge/lang/*.json` を生成して
  GeyserExtra のパック先行登録に食わせる。上記「`texts/*.lang` を書くのは GeyserExtra 側」）だが、
  **lang を置くだけでは既存の登録は 1 件も直らない**。台帳（`custom_items.json`）の
  英語フォールバック名エントリを先に消す必要がある（上記「lang を足しただけでは
  既存の登録は 1 件も直らない」＋ `ops/scripts/prune-geyser-auto-items.ps1`）。
  バニラ名表を作る案は `item.minecraft.*` を書くことになり
  **バニラアイテムの名前を潰す**ので採らない。

### 統合版でオフハンドに置けるかは「Bedrockカスタムアイテムとして登録されたか」で決まる
登録済みアイテムには `allow_offhand: true` が付く。**未登録アイテムはバニラのベースアイテム扱いになり、
Bedrockのオフハンド許可はバニラのごく一部（盾/トーテム/地図/矢等）に限られる**ため置けない。
「統合版だけオフハンドに置けない」報告を見たら、コードでなくリソースパック/登録側の不備
（モデルはあるのに未登録、モデル自体が無い等）を疑うこと。直し方はリソースパックの
補完・再生成・再配備であり、コード側の修正ではないことが多い。

## Java→Bedrock ジオメトリ・アニメーション変換の原理

### ⚠️ Bedrockは左腕アタッチャブルをミラーしない（オフハンドもX反転が必要）
Bedrock側にはJavaのようなミラー機構が無いため、オフハンド（左腕）も本手と同じくXを反転する
（`mirrorX=true` 固定）。「Javaの-1とBedrockのXミラー-1が相殺するので宣言値そのまま」という
判断は誤り。X=0付近（三人称root）ではミラー不変で症状が隠れ、Xの大きいモデルで初めて
「オフハンドが実質2倍右にずれる」形で表面化する。**一人称のroot姿勢は左右非対称
（`[90,60,-40]`等）なので、必ず手動でミラー処理（rotation `(x,-y,-z)` / position `(-x,y,z)`）を
適用する必要がある**（三人称が正しく見えても一人称の対称性は保証されない）。

### Javaの左手則（`ItemTransform#apply`時のleftHand反転）は無条件で働く
`*_lefthand` の宣言があってもなくても、バニラは leftHand=true のとき rotation Y/Z を反転し
translation X の符号を反転する（`*_lefthand` 未宣言時は右手のtransformオブジェクトを
そのまま代入するため、反転処理自体は必ず通る）。「`*_lefthand` が宣言されているから
反転しない」という前提は誤り。パック作者が左手スロットに事前補正済みの値
（右手 `+90` に対し左手 `-90` 等）を書いているのは、この無条件反転を見越しているため。

### geometry format 1.21.0（per-face uv_rotation）を使うなら manifestの `min_engine_version` も揃える
per-faceの `uv_rotation` を出力すると geometry は `format_version 1.21.0` になる。この時
パックの `manifest.json` の `min_engine_version` も `[1,21,0]` に上げないと、クライアントが
古い解釈で読んでper-face UVが壊れ、**3Dモデルのテクスチャだけ総崩れになる**
（flatモデルは無傷なので「3Dだけ全部おかしい」という症状で出る）。

### ⚠️ Bedrockは親boneのscaleが子boneのpositionにも掛かる（Javaは掛からない）
Javaの `ItemTransform#apply` は手座標系で「平行移動→回転→モデルをscale」の順なので、
平行移動はscaleの影響を受けない。しかしBedrockは親boneのscaleが子bone位置にも掛かる構造のため、
root scaleをそのまま持ち込むとJavaのdisplay平行移動が数倍に膨らみ、大型武器のモデルが
画面外へ飛ぶ。
**正しい規則**: 子boneのposition = 変換後平行移動 ÷ root scale。root base poseは全アイテム
共通の固定値とし、アイテムごとの差はJava側のdisplay.scaleだけに持たせる（root補正に
display.scaleを持ち込むと同じ情報の二重計上になる）。

### 一人称translationは軸ごとの符号反転だけでは足りない（frame変換が必要）
per-axisの符号反転が厳密に正しいのはroot回転が座標軸に揃っている場合だけ
（三人称root `[90,0,0]` は揃っているので単純な符号反転で正しい）。一人称root
`[90,60,-40]` のように揃っていない場合、Javaの1軸の移動がBedrock側では複数軸に分散する。
誤差はtranslationの大きさに比例するため、translationがほぼ0のアイテムでは症状が出ず、
大型武器（Zが飛び抜けて大きい等）でだけ表面化する。実装は三人称の実測出力から一意に
逆算した写像行列（`firstPersonTranslationFrame`、実機確認済みの値は `zxy`）を使うこと。
合成順序は理論だけでは決まらないため、**必ず実機で1回振って符号・順序を確定させる**。

### 一人称のY軸は三人称・headと逆向き（実機確認必須、類推禁止）
一人称rootのposition Yは、減らすと画面上（手から離れる）方向へ動き、増やすと手に近づく。
三人称は「上げると上に見える」ため、そこから類推すると符号が逆になる。
root回転が軸に揃っていないため見た目のズレをピクセル換算で符号決定することもできない。
**教訓: 一人称アームフレームの軸の向きは他フレームから導出できない。必ず実機で1回振って
符号を確定させること。**

### ⚠️ Blockbenchの自由回転`{x,y,z}`を単軸`angle`/`axis`前提のコードが無言で無視する
Blockbenchは要素回転を単軸 `{angle, axis, origin}` だけでなく自由回転 `{x, y, z, origin}` でも
出力する。`angle`/`axis` を前提にしたコードは自由回転時に `angle==0 && axis==null` を
「回転なし」と誤読し、**例外も警告も出さずに回転ゼロとして通す**。
これにより「モデルは正しいのにテクスチャだけずれる」（geometry変換側）と
「インベントリアイコンが縞模様になる」（icon描画側）という、一見無関係な2つの症状が
同時に発生し得る。`[-180, θ, 180]` のような合成可能な自由回転は単軸 `Ry(-180-θ)` に
畳めるため、Bedrockへ渡す前に単軸へ還元するのが正解（行列距離で一致を探す）。
**要素回転を読むコードを書くときは必ず `euler()` 分岐を先に書き、`angle()==0` を
無条件で「回転なし」と読まないこと。**

### Java→Bedrock変換の一次情報源は2つあり、食い違う箇所がある
- GeyserMC Rainbow（公式・現行）: `rainbow/.../mapping/{geometry/GeometryMapper,animation/AnimationMapper}.java`
- Kas-tle java2bedrock.sh（単一ファイル `converter.sh`。GeyserExtraはここから移植した経緯がある）

一致点: cube originはXミラー、Zは`-8`シフト、Yはそのまま。up/down面のUVはpoint mirror。
UVスケールは `PNG幅/16`。
食い違い（実機検証が必要）: cube回転のY符号（j2bは`-angle`、Rainbowは`+angle`。Rainbowが新しい）、
cube boneのpivot（RainbowはAABB中心、Java準拠実装は`[0,8,0]`）、一人称の構造
（Rainbowは単boneと軸置換、j2bはroot付き多段bone）。
罠ではないと判明済みの誤仮説: Blockbenchの `"texture_size":[32,32]` があってもface `uv`実値は
0..16レンジに正規化されているため、`texture_size`で追加補正してはいけない。

## 関連
- [./config-editor.md](./config-editor.md)
- [./common-traps.md](./common-traps.md)

## CMD 付きアイテムは、バニラの「描画構造」まで引き継がないと板ポリになる（2026-08-03）

1.21.4 以降の `assets/minecraft/items/<material>.json` は、単なるモデル指定ではなく
**描画の分岐ツリー**である。マテリアルによっては、同じアイテムが状況で別モデルになる:

| マテリアル | バニラの構造 | 捨てると失うもの |
|---|---|---|
| `trident` | `display_context` で GUI と手持ちを分け、手持ち/投擲は `minecraft:special` の専用レンダラ | **立体モデルと投擲アニメーション**（板ポリ化） |
| `bow` | `using_item` + `use_duration` で `bow_pulling_0/1/2` を切替 | **引き絞りの3段階**（引いてもモデルが変わらない） |
| `*_spear` | `display_context` で GUI アイコンと `*_spear_in_hand` を分ける | **槍の構え**（GUI 用の姿勢のまま持つ） |
| 防具 | `trim_material` で鍛冶型ごとのモデル | アイコンの鍛冶型差（描画コンテキスト由来ではないので TF は意図的に畳んでいる） |

**CMD エントリを素の `minecraft:model` 1個へ潰すと、この構造ごと消える。**
`fallback`（CMD なし＝バニラ品）だけは正しいままなので、**素の鉄の剣を見ても気づけない**。
実際 2026-08-03 まで、カスタムのトライデント10種・弓10種・槍3種が全部これで壊れていた。

- 正しいやり方は `tools/config-editor/lib/respack.js` の `entryModelFor()`:
  バニラのツリーを複製し、`minecraft:model` リーフだけをカスタムへ差し替える。
  `minecraft:special` は**テクスチャを差し替える手段がそもそも無い**ので触らない
  （＝カスタムトライデントの手持ち見た目はバニラ固定。GUI アイコンだけ独自にできる）。
- 自動生成モデルの `parent` は `handheld` / `generated` の2択にしてはいけない。
  バニラは弓・メイス・槍で **別々の display 変換**を持つ（`item/bow` / `item/handheld_mace` /
  `item/spear_in_hand`）ので、2択だと全部剣の構えになる。`parent` はそのマテリアルの
  バニラモデル id をそのまま指し、`layer0` だけ差し替えるのが正。
- バニラ側の一次情報はクライアント jar
  (`~/AppData/Roaming/.minecraft/versions/1.21.11/1.21.11.jar`) から直接読むこと。
  `tools/config-editor/lib/vanilla-item-defs-1.21.11.json` は `items/*.json` の逐語コピーだが、
  `models/item/*.json`（parent と display）は入っていない。
- 退行は `resourcepack/build_item_pack.py` の `check_render_structure()` がビルド時に落とす。

## PDC 経路のアイテムは「値」で区別できない。識別スロットの選び方が全体を決める（2026-08-03）

Geyser v2 の述語には **PDC の値を見るものが無い**。PDC 経路で登録した定義は
`minecraft:custom_data` の**有無**しか判定できないので、同じベース素材に載った
PDC 定義は全部おなじスタックにマッチし、**先に登録された 1 個が総取り**する。
つまり「どの PDC キーを識別子として採るか」と「どの順で書き出すか」が、
そのまま統合版での見た目を決める。

- **TF は `trinityforge:catalog_id` と `trinityforge:bind_type` の両方を書く。**
  GeyserExtra はキー名の辞書順で最初に当たったものを識別子にしていたので
  `"b" < "c"` で `bind_type` が勝ち、台帳の TF エントリ 51 件が全部
  `trinityforge:tradeable` / `:soulbound` になっていた（＝素材ごとに 1 個へ潰れる）。
  修正は `CustomItemScanner.rankKeysByHint()`: **ヒント語のランク順**に走査し、
  `*_type` 系は最下位。識別用と表示名用の 2 経路が同じ関数を通るので食い違わない。
- 書き出し順は `ItemMappingRegistry.save()` の `specificityRank`:
  CMD → `item_model` → 個別 PDC → 3 素材以上に跨る PDC。
  **古いエントリを消して直してはいけない**。PDC 経路のエントリは
  「誰かが実物を持つ」まで再生成できないので、削除は不可逆。
- 退行検知: `ItemMappingRegistrySpecificityOrderTest` /
  `PdcKeyHintRankingTest`（どちらも geyserExtra 側）。
  この 2 本が無いと、壊れても**統合版クライアントを実機で見るまで気づけない**。

### pdc_hints — レシピを持たないアイテムを先に登録する

PDC でしか識別できないアイテムは、ベースアイテムを宣言する手段がリソースパックに
無いため、GeyserExtra の先回り登録は CMD と `item_model` の 2 経路しかなかった。
残りは「誰かが実物を持つ」「レシピの材料か結果に現れる」まで台帳に載らないので、
**モブドロップ・ダンジョン報酬・商人販売のアイテムはどの経路にも掛からない**
（＝統合版で名前が識別子のまま・オフハンド不可）。

- 生成側: `resourcepack/build_pdc_hints.py` → `resourcepack/dist/trinityforge-catalog-pdc-hints.json`
- 読み手: geyserExtra の `PdcHintsReader`（`plugins/GeyserExtra/pdc_hints/*.json` を起動時に読む）
- **sanitize 規則を両側で合わせること。** ずれると先回り分と実物分が別 id で二重に載る。
  Java 側は `CustomItemScanner.sanitizeForStableId`（小文字化・空白/ハイフン/コロン→`_`・
  残りは `[a-z0-9_]` のみ・2〜64 文字）。
- 反映は Paper 書込 → **プロキシ再起動**の 2 段階（拡張は初期化時に 1 回しか読まない）。
