"use strict";

// 隔離済み。このパスは実行できない。
// 出荷 item-stats.yml を丸ごと上書きする旧 generator は
// scripts/DEPRECATED/generate-item-stats.js へ移した。
// 表を書き換えたいときは yml を直接編集するか、設定エディタから保存する。
console.error(`generate-item-stats.js は隔離済みで実行できません。

  出荷 item-stats.yml が真源です。このスクリプトは tiers 表が古いため、
  走らせると手調整と日本語コメントが無言で消えます。

  参照用の旧ソース: scripts/DEPRECATED/generate-item-stats.js
  （こちらも --force 無しでは動きません）`);
process.exit(1);
