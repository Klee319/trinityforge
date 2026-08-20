# arspaper/sourcelinks.yml

出荷config `fork-handoff/arspaper/fork/src/main/resources/sourcelinks.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

```
    # Minimal (1): sticks, bamboo, carpets, slabs, planks
```

```
    # Logs (3)
```

```
    # Standard (5): coal, charcoal
```

```
    # High (10): blaze rod, dried kelp block
```

```
    # Very high (45): coal block
```

```
    # Maximum (100): lava bucket
```

```
# --- Mycelial Sourcelink (SMOKER) ---
# Food items consumed to generate Source.
# Reference: cooked beef = 10 source points
```

```
    # Raw / simple foods (1)
```

```
    # Basic processed (3)
```

```
    # Medium (5)
```

```
    # Higher (7)
```

```
    # Special (10)
```

```
    # High (15)
```

```
    # Very high (30)
```

```
    # Maximum (75)
```

```
# --- Alchemical Sourcelink (BLAST_FURNACE) ---
# Brewing/alchemy materials consumed to generate Source.
# Reference: blaze powder = 8 source points
```

```
    # Basic (1)
```

```
    # Standard brewing (3)
```

```
    # Rare (8)
```

```
    # Very rare (15)
```

```
    # Potions
```

```
    # Maximum (30)
```

```
# =============================================================================
# items: ソースリンク本体の item-catalog 相当定義（見た目・識別・レシピ）
# recipe は Ars Nouveau 装置レシピを儀式 (method: ritual) に移植したもの。
#
# 固定5種以外の「カスタムソースリンク」も追加できる:
#   items.<任意id>:
#     type: volcanic | mycelial | alchemical | vitalic | botanical  # 挙動 (必須)
#     material: FURNACE  など見た目の定義は固定5種と同じ
# type のマテリアル/値テーブルは各typeの共有設定 (volcanic.materials 等) を使う。
# 追加は /ars reload で反映。削除の反映はサーバー再起動が必要。
# =============================================================================
```

```
    # バニラ Agronomic Sourcelink: Source Gem + Gold + Wheat → 儀式移植
```
```
# ===========================================================================
# 上位ソースリンク階梯 (2026-08-02 追加/2026-08-03 拡張)
# ---------------------------------------------------------------------------
# 【解消した問題 その1: レート】それまでソースリンクは5種が1個ずつしか存在せず、転送レート
#   (max-per-transfer)は全リンク共通の1値だったため、レートを上げる唯一の手段が
#   「同種リンクを並べて置くこと」になっていた(K-16)。
#
# 【解消した問題 その2: 生成量】2026-08-02 の階梯は転送レートしか上げなかったので、
#   上位リンクへ燃料を1個焼べても得られるソースは無印と同じ = 素材効率が階梯で改善しなかった。
#   2026-08-03 に yield-multiplier を追加して「生成量」も階梯で伸びるようにした。
#
# 【2つの倍率の違い】
#   transfer-multiplier: バッファ→隣接ジャーへ1周期に出せる量に掛かる(Sourcelink#effectiveMaxPerTransfer)。
#     速く運べるようになるだけで、素材1個から得られるソースは変わらない。
#   yield-multiplier:    新しく生まれる量に掛かる(SourceGenerationScaling)。
#     燃料/食料/素材の投入(手投入・ホッパー投入の両方)、バイタリックの受動生成、
#     ボタニカルの成長ボーナスとバイタリックの撃破ボーナス ── この4経路すべてに掛かる。
#     ⚠ 「注ぎ切れずにバッファへ戻す分」には掛からない。掛けると隣接ジャーが満杯の間だけ
#        毎周期ソースが増える無限増殖になる(理由の全文は SourceGenerationScaling の javadoc)。
#   どちらも未設定なら 1.0 = 無印と完全に同じ挙動。0以下/非有限値は警告のうえ 1.0 へ落とす。
#
# 【段の設計】転送 x2/x4/x8/x16、生成 x1.5/x2/x3/x4。生成量倍率を転送より緩くしているのは、
#   生成量は素材効率そのもの(=無限資源に近づく)なので、速度より伸びを抑えたいため。
#
# 【レシピの考え方】sourcejars.yml の上位ジャーと同じ「前段を炉に、階梯触媒を台座に」形。
#   core-item は必ず前段の custom:<type>_sourcelink[_ii..] にして、既存レシピと
#   (core-item, pedestal-items) の組が重複しないようにしている(重複すると後発が
#   findFirst に負けて永久にクラフト不可になる ── common-traps.md 参照)。
#   階梯触媒(source_shard/crystal/condenser/engine)は sourcejars.yml の上位ジャーと同じ物を使う
#   (ソース階梯のはしごを一本化するため、ソースリンク専用の新触媒は作らない)。
#
# 【名前】数字表記(II/III)は 2026-08-03 に固有名へ置き換えた。id は据え置き
#   ── id を変えるとレシピ(core-item: custom:..._ii)と設置済みブロックのPDCが全部切れる。
#
# 【見た目】custom-model-data は未設定 ── 割り当てるとリソースパック側(resourcepack/*、
#   別レーンが編集中)に新規CMD登録が要るため、今回は据え置く。無印と同じモデルで表示される
#   (display-name/lore と実際の block id で区別できるので機能面は問題ない)。見た目の分離は
#   リソースパック側の残タスクとして報告する。
# ===========================================================================
```
