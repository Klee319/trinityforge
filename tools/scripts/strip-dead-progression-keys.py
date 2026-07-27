# -*- coding: utf-8 -*-
"""skills/base/*_progression.yml から ValhallaMMO 時代の死にデータを取り除く。

これらのファイルは「ValhallaMMO の jar 既定値を vendor したもの」で、TF が実際に読むのは
experience.max_level / exp_level_curve と各種 EXP テーブルだけ。残りは移植時にそのまま持ってきた
まま誰も読んでいない。

除去対象は「Java 側に文字列リテラルとしての参照が1つも無い」ことを確認したキーのみ:
  - SkillCatalogEntry.rate(String, double) の呼び出しは全て文字列リテラル(変数キーは0件)なので、
    literal grep が 0 件 = 本当に到達不能、と言い切れる。
  - prestige_decay_rate は consumers=2 で生きているため対象外(名前が似ているので注意)。

冪等。既に消えているファイルは変更なしと報告する。
"""
import glob
import io
import os
import re

DEAD_KEYS = [
    "daily_limit", "daily_limit_decay_percent", "daily_limit_warning",
    "pvp_multiplier", "is_chunk_nerfed", "spawner_spawned_multiplier",
    "max_health_limitation", "durability_chunk_limit", "diminishing_returns",
    "mace_exp_multiplier", "infinity_multiplier",
]


def indent_of(line):
    return len(line) - len(line.lstrip(" "))


def strip_keys(text):
    """キー行と、それにぶら下がる(より深いインデントの)行をまとめて落とす。"""
    lines = text.split("\n")
    out = []
    skip_deeper_than = None
    removed = 0
    for line in lines:
        if skip_deeper_than is not None:
            # 空行はぶら下がり判定に使えないので、次の実体行まで保留せずそのまま落とす。
            if line.strip() == "" or indent_of(line) > skip_deeper_than:
                continue
            skip_deeper_than = None
        m = re.match(r"^(\s*)([A-Za-z0-9_]+):", line)
        if m and m.group(2) in DEAD_KEYS:
            skip_deeper_than = len(m.group(1))
            removed += 1
            continue
        out.append(line)
    return "\n".join(out), removed


def main():
    base = os.path.join(os.path.dirname(__file__), "..", "..",
                        "TrinityForge", "src", "main", "resources", "skills", "base")
    total_files = total_keys = 0
    for path in sorted(glob.glob(os.path.join(base, "*_progression.yml"))):
        original = io.open(path, encoding="utf-8-sig").read()
        cleaned, removed = strip_keys(original)
        if removed:
            io.open(path, "w", encoding="utf-8", newline="\n").write(cleaned)
            total_files += 1
            total_keys += removed
            print("  %-34s -%d keys" % (os.path.basename(path), removed))
    print("変更ファイル=%d 除去キー=%d" % (total_files, total_keys))


if __name__ == "__main__":
    main()
