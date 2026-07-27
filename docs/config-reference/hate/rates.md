# hate/rates.yml

出荷config `TrinityForge/src/main/resources/hate/rates.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### 直後: `max-tracked-mobs: 5000`

```
Hard cap on simultaneously tracked mobs. Oldest-touched mob is evicted (LRU) past this.
```

### 直後: `max-attackers-per-mob: 64`

```
Hard cap on attackers tracked per mob. Lowest-threat attacker is evicted past this.
```

### 直後: `enabled: false`

```
Master switch for time-based threat decay. OFF by default (balance-neutral); turning it on
is a balance decision, not part of the leak fix.
```

### 直後: `per-second: 0.0`

```
Fraction of remaining threat removed per second when decay is enabled (0.0 - 1.0).
0.0 = no decay even when enabled.
```

### 直後: `entry-ttl-seconds: 600`

```
Drop an attacker entry that has had no new threat for this many seconds. 0 disables the TTL.
600s only reclaims long-abandoned entries; it never affects an active fight.
```

### 直後: `interval-ticks: 1200`

```
How often (in ticks, 20 = 1s) the async sweep applies decay and reclaims stale entries.
```

### 直後: `per-damage: 1.0`

```
Threat generated per point of damage dealt. Neutral 1:1 default; this is the only rate knob
here and is intentionally minimal until the hate-balance design (R2) lands.
```

