r"""PreToolUse guard (TrinityForge project). K approved 2026-08-04.

A: D:\game (稼働サーバ) への書き込みを拒否する。
   - Write/Edit の file_path が D:\game 配下
   - Bash/PowerShell の書き込み系コマンド × D:\game
   読み取り（ログ調査など）は許可のまま。配備スクリプトはエージェントが書き、
   実行はユーザーが行う（CLAUDE.md）。
B: git add -A / --all / add . / git commit -a を拒否する。
   同一ワークツリーで複数セッションが並行作業するため、他人の未コミット変更を
   巻き込む（CLAUDE.md）。自分が触ったパスだけを列挙して add する。

Input: hook JSON on stdin. Output: permissionDecision JSON on stdout.
Fail-open: 予期しないエラーは exit 0（allow）。このスクリプトのバグでツールを
壊さないため。
"""
import json
import re
import sys

DGAME = re.compile(r"d:[\\/]+game", re.IGNORECASE)
WRITE_VERBS = re.compile(
    r"\b(cp|copy|xcopy|robocopy|mv|move|del|erase|rm|rd|rmdir|mkdir|md|"
    r"ni|new-item|copy-item|move-item|remove-item|set-content|add-content|"
    r"out-file|tee|touch|unzip|tar|7z|expand-archive|compress-archive)\b",
    re.IGNORECASE)
REDIRECT_TO_DGAME = re.compile(r">+\s*['\"]?d:[\\/]+game", re.IGNORECASE)
GIT_ADD_ALL = re.compile(
    r"\bgit\b[^\n|;&]*\badd\b[^\n|;&]*(\s-A\b|\s--all\b|\s\.(\s|['\"]?$))")
GIT_COMMIT_A = re.compile(
    r"\bgit\b[^\n|;&]*\bcommit\b[^\n|;&]*\s(-a\b|-am\b|--all\b)")


def decide(decision, reason):
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": decision,
            "permissionDecisionReason": reason,
        }
    }))
    sys.exit(0)


def main():
    data = json.load(sys.stdin)
    tool = data.get("tool_name", "")
    ti = data.get("tool_input") or {}

    if tool in ("Write", "Edit"):
        fp = (ti.get("file_path") or "").strip()
        if re.match(r"^['\"]?d:[\\/]+game", fp, re.IGNORECASE):
            decide("deny",
                   "Blocked: D:\\game (稼働サーバ) への書き込みは禁止。"
                   "稼働中の jar 差し替えは NoClassDefFoundError で復旧不能になる。"
                   "配備スクリプトをリポジトリ内 (ops/ or tmp/) に書き、"
                   "実行はユーザーに依頼すること (CLAUDE.md)。")
        return

    if tool not in ("Bash", "PowerShell"):
        return

    cmd = ti.get("command") or ""

    if GIT_ADD_ALL.search(cmd) or GIT_COMMIT_A.search(cmd):
        decide("deny",
               "Blocked: git add -A / --all / add . / commit -a は禁止。"
               "同一ワークツリーで複数セッションが並行作業しており、"
               "他セッションの未コミット変更を巻き込む。"
               "自分が触ったパスだけを列挙して git add すること (CLAUDE.md)。")

    if DGAME.search(cmd) and (WRITE_VERBS.search(cmd)
                              or REDIRECT_TO_DGAME.search(cmd)):
        decide("deny",
               "Blocked: この Bash/PowerShell コマンドは D:\\game (稼働サーバ) へ"
               "書き込む可能性がある。読み取り (ログ調査等) は許可されているが、"
               "書き込み・コピー・削除は禁止。配備スクリプトをリポジトリ内に書き、"
               "実行はユーザーに依頼すること (CLAUDE.md)。")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        sys.exit(0)
    sys.exit(0)
