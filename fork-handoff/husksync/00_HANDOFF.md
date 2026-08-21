# HuskSync フォーク — 引き継ぎ

**目的はただ1つ**: 復元できないアイテムが 1 個混ざっただけで**インベントリを丸ごと捨てる**
上流の挙動を、**そのスロットだけ落として警告する**挙動に変える。

上流に手を入れたのはこれだけで、他は一切触っていない。上流へ追随するときも、
`patches/` の 1 本を当て直すだけで済むようにしてある。

## 何が起きていたか（2026-08-21 の実障害）

`Resource_Server/world/datapacks/` にだけ **Dungeons and Taverns v5.1.0** ほかが入っており、
`nova_structures:` のカスタムエンチャント 34 種を登録している。
**Main_Server / Dev_Server の `world/datapacks/` は `bukkit` だけ。**
資源鯖で拾った DnT エンチャ品を持って Main へ移動すると:

```
[11:24:38] [HuskSync/WARN]: Failed to deserialize %s data for snapshot %s; skipping it.
           ... The player will load without this data type for this session.
NbtApiException: Failed to convert NBT to ItemStack.
  DataResult.Error['Failed to get element nova_structures:spiteful ...
  {DataVersion:4671,Slot:16,components:{"minecraft:stored_enchantments":{...}},id:"minecraft:enchanted_book"}
```

**インベントリが丸ごと来ない。** その後の save で snapshot が上書きされて恒久ロストになる。
proxy のログでも `.shizurei555` が 11:24:37 に resource → main へ移動した**1秒後**に一致している。

## 直したところ

`bukkit/src/main/java/net/william278/husksync/data/BukkitSerializer.java` の
`ItemDeserializer#getItems` は、スナップショットのバージョンがサーバと同じなら
`NBT.itemStackArrayFromNBT(tag)` で**一括**復元していた（＝1個落ちたら全部落ちる）。

一括読みを `try` で包み、落ちたら**上流が既に持っているスロット単位の読み直し**
（`upgradeItemStacks`）へ回す。あちらは 1 スタックずつ try/catch していて、
最終的に `Material.AIR` へ落とすので、**壊れたスロットだけが消える**。
ついでに、黙って AIR にしていた箇所へ「どのスロットの何を落としたか」の WARNING を足した
（返却や復元の判断材料になる。無いと何が消えたか永久に分からない）。

**上流のロジックを新規に書き起こしてはいない**。既存の耐性パスへ合流させただけ。

## ビルドと配備

| 項目 | 値 |
|---|---|
| 上流 | `https://github.com/WiIIiam278/HuskSync.git` |
| ベース commit | `3dc619d`（**配備中の jar と同じ**。master は MC 26.2 向けなので使わない） |
| ブランチ | `trinityforge/tolerate-unreadable-items` |
| JDK | **25**（`gradle.properties: javaVersion=25`）。TF 本体の jdk-21 とは違う |
| タスク | `:bukkit:1.21.11:build`（multi-version ビルド。MC バージョンごとにサブプロジェクトがある） |
| 成果物 | **`target/HuskSync-Bukkit-*+mc.1.21.11.jar`** |

⚠️ **`bukkit/1.21.11/build/libs/` にも同名の jar が出るが、そちらは依存を同梱していない
thin jar（153KB）で起動できない。** shadowJar の出力先はルートの `build.gradle` で
`$rootDir/target` に付け替えられている。配るのは **3.2MB の方**。

```bash
# 取得（fork/ は .gitignore 除外。clone にも新しい worktree にも存在しない）
git clone https://github.com/WiIIiam278/HuskSync.git fork-handoff/husksync/fork
cd fork-handoff/husksync/fork
git fetch --unshallow origin      # --depth 1 だと 3dc619d が取れない
git checkout -b trinityforge/tolerate-unreadable-items 3dc619d
git apply ../patches/0001-tolerate-unreadable-items.patch
```

配備（**サーバ停止後**。実行はユーザー）:

```
ops\launch\deploy-husksync.cmd -DryRun
ops\launch\deploy-husksync.cmd
```

配備スクリプトは (1) jar の中の `BukkitSerializer$ItemDeserializer.class` に目印文字列が
あるかを見て**パッチ未適用の jar を配るのを拒否**し、(2) バックエンドの停止をゲートし、
(3) **今入っている jar と同じ名前で**置く（別名で置くと HuskSync が 2 本ロードされる）。
旧 jar は `.jar.bak-<日時>` へ退避する（Paper は `.jar` しか読まないので安全）。

## これで直らないこと

パッチは**全ロストを止めるだけ**で、`nova_structures:` のエンチャント品そのものは
Main/Dev では復元できないまま（そのスロットは AIR になる）。
**エンチャント品を持ち帰れるようにしたいなら、3 バックエンドでエンチャントの registry を
一致させる**しかない（DnT 本体を Main/Dev にも入れる／worldgen を落とした registry 専用
パックを作る、のどちらか）。これは別判断。
