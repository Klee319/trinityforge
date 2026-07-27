# skilltree/fishing.yml

出荷config `TrinityForge/src/main/resources/skilltree/fishing.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### 直後: `max-times: 1` (2026-07-26 追加。監査knowledge: prestige.max-times が出荷ymlに存在せず全ツリー1周固定だった件)

```
周回上限(NG+)。SkillTreeConfig#load が 'max-times' を section.getInt("max-times", 1) で読む
既存の実装済みロジックに対応する値だが、出荷ymlに一度もキーが存在しなかったため全ツリーが
常にデフォルト値1(1周のみ)に固定されていた(監査知見)。ここで明示化した値も1であり、
挙動は変更していない。2周目以降を解禁したい場合はこの値を2以上に上げる。
```

### 直後: `starting-coords:`

```
# 排他ルート(実装は group による相互排他で強制。この行は説明のみ): ギリシャ文字路線(alpha=ゴミ/スクラップ経済 / beta=ガチャ券/宝)はいずれか1つのみ解放可能(推定)
```

### 直後: `nodes:`

```
  # ---- 主軸 A〜E（主軸に固有名の記載が画像に無いため世界観で命名） ----
  # ---- 派生ノード（最下段：海釣り C系） ----
      # T4(2026-07-25): 海洋系バイオーム限定でfishing-bonus(=釣果の追加ドロップ期待値="同時ヒット")へ
      # 加算される。対象バイオーム一覧は stats/fishing-gimmick.yml の fishing.ocean-biomes で構成可能。
  # ---- ギリシャ文字路線（上段 alpha=ゴミ/スクラップ経済・排他） ----
    # 2026-07-25 経済連携(Vault対応)で復活: 釣った魚を自動売却(FishSellListener) +
    # 宝抽選をゴミ抽選へ丸ごと差し替え(FishingGimmickListener、fishing.groups設定時のみ)。
      # 2026-07-25 経済連携: 「スクラップ変換効率」=解体の戻り係数バフ、「魚の売却価格」=売却額倍率。
      # ともに割合(0.0〜1.0)。disassembly_return_bonusは装備解体(dismantle-unlock)側の戻り量に、
      # fish_sell_price_bonusはfish-sell-toggleの売却額に、それぞれ追加乗算として作用する。
  # ---- ギリシャ文字路線（下段 beta=ガチャ券/宝・排他。転写では派生ノード欄だがギリシャ命名） ----
```
