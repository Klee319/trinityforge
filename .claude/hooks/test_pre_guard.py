# -*- coding: utf-8 -*-
"""pre-guard.py の判定を実測で固定する (2026-08-20 W-177)。

このフックが no-op に戻ったことは【誰も気付けない】。deny が消えても
「コマンドが通った」だけで、壊れるのは他セッションかユーザーの未コミット変更だから、
気付くのは何日か後に「設定が勝手にロールバックした」と報告されたときになる。
だから許可側・拒否側の両方を実際にフックへ流して確かめる。

実行:
    python -m pytest .claude/hooks/test_pre_guard.py -q
"""
import json
import subprocess
import sys
from pathlib import Path

import pytest

HOOK = Path(__file__).resolve().parent / "pre-guard.py"


def run_hook(tool: str, tool_input: dict) -> str:
    """フックを実際に起動して permissionDecision を返す。未出力なら "allow" とみなす。"""
    payload = json.dumps({"tool_name": tool, "tool_input": tool_input})
    proc = subprocess.run(
        [sys.executable, str(HOOK)],
        input=payload, capture_output=True, text=True, encoding="utf-8",
    )
    assert proc.returncode == 0, f"フックが異常終了した: {proc.stderr}"
    out = proc.stdout.strip()
    if not out:
        return "allow"
    return json.loads(out)["hookSpecificOutput"]["permissionDecision"]


def bash(cmd: str) -> str:
    return run_hook("Bash", {"command": cmd})


# ---- C: ワーキングツリーを捨てる git (W-177) ----------------------------------------------------

@pytest.mark.parametrize("cmd", [
    "git checkout -- TrinityForge/src/main/resources/items/catalog.yml",
    "git checkout TrinityForge/src/main/resources/items/catalog.yml",
    "git checkout .",
    "git checkout HEAD -- src/main/resources/network.yml",
    "cd TrinityForge && git checkout -- src/main/java/com/trinityforge/TrinityForge.java",
    "git restore TrinityForge/src/main/resources/gacha.yml",
    "git restore .",
    "git reset --hard origin/dev",
    "git clean -fd",
    "git clean -fdx",
    "git stash",
    "git stash push -- TrinityForge/src/main/resources",
    "git stash save wip",
    "git stash clear",
    "git stash drop",
])
def test_worktree_destroying_git_is_denied(cmd):
    assert bash(cmd) == "deny", cmd


@pytest.mark.parametrize("cmd", [
    # ブランチ操作は通す。これを塞ぐと日常作業が止まる。
    "git checkout dev",
    "git checkout -b feat/w177",
    "git checkout -B dev origin/dev",
    # 読むだけ・index だけ。ワーキングツリーは消えない。
    "git stash list",
    "git stash show -p stash@{0}",
    "git stash pop",
    "git stash apply stash@{1}",
    "git restore --staged TrinityForge/src/main/resources/gacha.yml",
    "git show HEAD:TrinityForge/src/main/resources/items/catalog.yml > tmp/head-catalog.yml",
    "git diff TrinityForge/src/main/resources/items/catalog.yml",
    "git reset --soft HEAD~1",
    "git status --porcelain",
])
def test_harmless_git_is_allowed(cmd):
    assert bash(cmd) == "allow", cmd


# ---- A/B: 既存のガードが生きていること ----------------------------------------------------------

@pytest.mark.parametrize("cmd", [
    "git add -A",
    "git add --all",
    "git add .",
    "git commit -am fix",
])
def test_git_add_all_still_denied(cmd):
    assert bash(cmd) == "deny", cmd


def test_dgame_write_still_denied():
    assert bash('copy build\\libs\\TrinityForge.jar "D:\\game\\minecraft\\PaperServer\\x"') == "deny"


def test_dgame_read_still_allowed():
    assert bash('tail -n 50 "D:/game/minecraft/PaperServer/Main_Server/logs/latest.log"') == "allow"


def test_write_into_dgame_denied():
    assert run_hook("Write", {"file_path": "D:\\game\\minecraft\\PaperServer\\x\\config.yml"}) == "deny"


def test_write_into_repo_allowed():
    assert run_hook("Write", {"file_path": "TrinityForge/src/main/resources/network.yml"}) == "allow"
