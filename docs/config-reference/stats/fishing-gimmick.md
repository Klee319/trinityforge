# stats/fishing-gimmick.yml

出荷config `TrinityForge/src/main/resources/stats/fishing-gimmick.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### 直後: `treasure-materials:`

```
後方互換フォールバック専用(上記と同じ条件)。
```

### 直後: `fish-sell:`

```
fish-sell-toggle (flag, fishing.yml B-alpha-1): 釣った魚を釣った瞬間に自動でVault通貨へ換金する
(2026-07-25 経済連携。EconomyBridge経由、Vault不在時は静かに無効化=通常どおりアイテムとして入手)。
```

### 直後: `prices:`

```
キー: バニラMaterial名 または カスタムアイテムID(fishing.groups.<...>.categories.*.entries[].item と
同じトークン語彙。T2 2026-07-25経済連携拡張で fish 枠のカスタムアイテムにも売却額を設定可能にした)。
未登録のトークンは0円=売却対象外(通常どおりアイテムとして入手)。
プレイヤーの fish_sell_price_bonus stat (B-alpha-3) はこの基準額に対する倍率として乗算される。
数値は全て暫定(要調整)。
```

### 直後: `max-sells-per-minute: 10`

```
exploit対策(#5 exploit fixの教訓。AFK釣り機/自動釣りマクロでの無限換金を防ぐ):
1プレイヤーが1分間に自動売却できる回数の上限。
実プレイの釣り速度は概ね毎分2〜6匹なので、10なら通常プレイに影響せずAFK釣り機だけを抑えられる。
```

### 直後: `xp-bottle-store:`

```
xp-bottle-store-unlock (flag, enchanting.yml B-3): 経験値瓶への経験値の格納/取出。
```

### 直後: `store-amount: 100`

```
sneak+右クリックで経験値瓶1本に格納する経験値量(格納量 > 保有経験値の場合は保有量が上限)。
```

### 直後: `return-rate: 1.0`

```
取り出し時に返る割合(0.0-1.0)。floor(格納量 × return-rate)。1.0=目減りなし。
```

### 直後: `fishing:`

```
釣り「宝/ゴミ比率＋釣り運シフト」機構(2026-07-23 stat-gate-overhaul §2.3/§4)。
CAUGHT_FISH時、この節が非空なら獲得物を丸ごと置換する(FishingGimmickListener)。
```

### 直後: `luck-per-level: 0.005`

```
FISHING Lv × この値 = 追加fishing-luck(宝率シフトにのみ使用。品質modeには使わない。
旧 stats/gathering.yml fishing.luck-per-level=0.05 は品質mode時代の値のため、比率専用化に伴い
0.005へ変更)
```

### 直後: `bonus-per-level: 0.02`

```
FISHING Lv × この値 = 追加fishing-bonus(非装備釣果の追加ドロップ期待値。旧 gathering.yml のまま)
```

### 直後: `ocean-biomes:`

```
2026-07-23 仕様確定: 釣果は「宝% / ゴミ% / 残り=通常の魚」の三択。
宝%はfishing_luck合算で相対的にシフトし、増えた分だけゴミ%・魚%が比例で減る(ゴミ%自体の
基準値=junk-percentはluckの影響を受けない。宝%とのバランスのみ変動)。
T1(2026-07-25): 「通常の魚」枠は groups.fish で設定可能(下記参照)。groups.fish が未設定(空)の
ままなら、通常の魚の抽選結果は旧来どおりバニラキャッチをそのまま維持する(後方互換)。
groups.fish を設定した場合、下の既定値はバニラの魚釣果テーブル(COD 60% / SALMON 25% /
PUFFERFISH 13% / TROPICAL_FISH 2%)をそのまま移植したもの — treasure/junk 両グループが
既に「バニラ○○(移植)」として明示移植済みであることとの一貫性を優先した。バニラのFISHING
ルートテーブル自体はエンチャント(入れ食い/宝釣り)の影響を受けない固定weightなので、
この移植による確率分布への副作用は無い(入れ食い/宝釣りが動かすのは上位の宝/ゴミ/魚の
三択比率側であり、fishing.yml の宝運/釣り運関連statがそちらに対応する)。
T4海釣り判定(2026-07-25): 釣り位置のバイオームがこの一覧に含まれる場合のみ ocean_fishing_bonus stat
(fishing.yml C-1/C-2)が fishing_bonus の期待値に加算される。バニラのバイオーム追加に追随できるよう
ハードコードせずここで管理する(レジストリキー、namespace無しの小文字表記)。省略時は下記既定値を使用。
```

### 直後: `treasure-percent: 5.0`

```
基準宝率(%)。fishing_luck合算 luckTotal で percent×(1+luckTotal) にシフトされ、
luckTotal==0のときはこの値がそのまま使われ(0/100も許容)、luckTotal!=0のときのみ
[0.05, 95.0]にクランプされる(luckTotalが極端でも厳密に0/100にはならない)。
```

### 直後: `junk-percent: 10.0`

```
基準ゴミ率(%、luckTotalによるシフトなしの固定値)。宝%がluckでシフトした分だけ
(100-シフト後宝%)/(100-基準宝%) の比率で比例縮小/拡大される。残り(100-宝%-ゴミ%)は
通常の魚としてバニラキャッチをそのまま維持する。
```

### 直後: `ocean_thread:`

```
旧 fork ArsPaper loot/OceanThreadFishingListener(ocean-thread-catch dedicated-effect)の挙動
移植: 同リスナーがドロップするのと同じ空スレッド item id (thread_empty、Ars item registry)。
```

### 直後: `scrap-exempt: true`

```
scrap-exempt: true (2026-07-23 verifier指摘⑥): junk-to-scrap保持者でもこのカテゴリの抽選結果は
tf_scrap への一律差し替え対象から除外する。除外しないと ocean_thread (空スレッド) が
junk-to-scrap保持者にとって恒久的に入手不可能になってしまうため。
```

### (ファイル末尾の補足)

```
fish: (T1 2026-07-25、「通常の魚」枠の設定可能化) — 意図的に既定は未設定(空)のまま出荷する。
未設定の間は旧来どおり、抽選が「通常の魚」に決まった回はバニラの釣果(FishHookが実際に生成した
ItemStack)をそのまま維持する。treasure/junk と違い、この枠はバニラの通常釣果(=CAUGHT_FISH時に
既にバニラ側で決定済みの実アイテム)をTF側の重み付き抽選で置き換えることになるため、設定すると
「バニラが実際に引いた具体的な1個」ではなく「TF側で独立に再抽選した同種アイテム」に変わる
(バニラの入れ食い/宝釣りエンチャントは宝/ゴミ/魚の三択比率側=このファイルの
fishing.group-ratio/fishing_luck statに対応する側に効くだけで、魚カテゴリ内のCOD/SALMON/
PUFFERFISH/TROPICAL_FISH の重み自体はバニラでも固定値なので統計的な確率分布そのものは
変わらない見込みだが、"vanilla任せ"から"TF管理"へ制御が移る意味では挙動変更にあたる)。
そのため既定では有効化せず、必要な管理者だけ下記のように明示的に設定する運用とした。
有効化する場合の移植例(バニラ既定のFISHING_FISHルートテーブルと同じ重み):
fish:
  categories:
    fish_vanilla:
      display-name: "バニラ魚(移植)"
      entries:
        - item: COD
          weight: 60
          amount: 1
        - item: SALMON
          weight: 25
          amount: 1
        - item: PUFFERFISH
          weight: 13
          amount: 1
        - item: TROPICAL_FISH
          weight: 2
          amount: 1
```

