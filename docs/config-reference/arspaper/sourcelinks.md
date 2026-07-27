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