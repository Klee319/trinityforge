r"""PreToolUse guard (TrinityForge project). K approved 2026-08-04.

A: D:\game (稼働サーバ) への書き込みを拒否する。
   - Write/Edit の file_path が D:\game 配下
   - Bash/PowerShell の書き込み系コマンド × D:\game
   読み取り（ログ調査など）は許可のまま。配備スクリプトはエージェントが書き、
   実行はユーザーが行う（CLAUDE.md）。
B: git add -A / --all / add . / git commit -a を拒否する。
   同一ワークツリーで複数セッションが並行作業するため、他人の未コミット変更を
   巻き込む（CLAUDE.md）。自分が触ったパスだけを列挙して add する。
C: ワーキングツリーの未コミット変更を捨てる git を拒否する（2026-08-20 W-177）。
   git checkout <path> / git restore / git reset --hard / git clean / git stash push。
   実害の記録: 2026-08-19 14:51、あるセッションがテストのために catalog.yml を HEAD へ
   戻し、その 33 秒前にユーザーが設定エディタで保存した「触媒(杖3種)の id とレシピ」を
   丸ごと消したうえ、自分の変更だけを commit した。ユーザーからは
   「設定が勝手にロールバックした」としか見えない。
   HEAD の内容でテストしたいときは ops\scripts\run-against-head.ps1 を使う
   （退避 -> HEAD を置く -> コマンド実行 -> 必ず戻す、を1コマンドでやる）。

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

# ---- C: ワーキングツリーを捨てる git ------------------------------------------------------------
# git が【コマンドの先頭】に来ているものだけを見る。行頭か、区切り(; && || | 改行 括弧)の直後。
# これを付けないと「git checkout -- <path> は禁止」と書いた文字列をエコー・保存するだけで
# deny になる。ドキュメントやメモを書けなくなるうえ、原因が分かりにくい(2026-08-20 に実際に踏んだ)。
# バックティックを区切りに含めないのはそのため: markdown の `git checkout ...` は素通しにする。
GIT = r"(?:^|[\n;&|(])\s*git\b"
#
# 各項目は (落とすパターン, 例外的に通すパターン, 表示名)。
# 例外側を「同じコマンド全体」で見るのが要点: git stash は `stash@{0}` の中にも
# \bstash\b が出るので、否定先読みだけで書くと `git stash apply stash@{0}` を誤爆する。
#
# git checkout はブランチ切替にも使うので、ファイルを指していると分かるものだけ落とす。
#   落とす : git checkout -- <path> / git checkout . / git checkout <rev> src/... / *.yml など
#   通す   : git checkout dev / git checkout -b feat/x / git checkout -B dev origin/dev
GIT_CHECKOUT_PATH = re.compile(
    GIT + r"[^\n|;&]*\bcheckout\b[^\n|;&]*"
    r"(\s--\s|\s\.(\s|$)|[\\/]|\.(yml|yaml|java|js|mjs|json|md|ps1|cmd|py|txt)\b)")
GIT_CHECKOUT_BRANCH = re.compile(GIT + r"[^\n|;&]*\bcheckout\b[^\n|;&]*\s-[bB]\b")
# git restore は既定でワーキングツリーを戻す。--staged だけなら index を戻すだけなので通す。
GIT_RESTORE = re.compile(GIT + r"[^\n|;&]*\brestore\b")
GIT_RESTORE_STAGED_ONLY = re.compile(GIT + r"[^\n|;&]*\brestore\b[^\n|;&]*--staged\b")
GIT_RESET_HARD = re.compile(GIT + r"[^\n|;&]*\breset\b[^\n|;&]*\s--hard\b")
GIT_CLEAN = re.compile(GIT + r"[^\n|;&]*\bclean\b[^\n|;&]*\s-[a-zA-Z]*[fdx]")
# git stash はワーキングツリーを退避「して消す」。list/show/pop/apply は消さないので通す。
GIT_STASH = re.compile(GIT + r"[^\n|;&]*\bstash\b")
GIT_STASH_READONLY = re.compile(
    GIT + r"[^\n|;&]*\bstash\b\s+(list|show|pop|apply)\b")

WORKTREE_DESTROYERS = (
    (GIT_CHECKOUT_PATH, GIT_CHECKOUT_BRANCH, "git checkout <path>"),
    (GIT_RESTORE, GIT_RESTORE_STAGED_ONLY, "git restore"),
    (GIT_RESET_HARD, None, "git reset --hard"),
    (GIT_CLEAN, None, "git clean"),
    (GIT_STASH, GIT_STASH_READONLY, "git stash (push/save/clear/drop)"),
)


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

    for pattern, exception, label in WORKTREE_DESTROYERS:
        if pattern.search(cmd) and not (exception and exception.search(cmd)):
            decide("deny",
                   "Blocked: " + label + " はワーキングツリーの未コミット変更を捨てる。"
                   "この worktree では複数セッションが並行作業し、さらにユーザーが設定エディタで"
                   "編集した yml が未コミットのまま置かれている（それを配備するのが正規の運用）。"
                   "2026-08-19 に catalog.yml でこれを踏み、ユーザーの触媒3種の id とレシピが消えた"
                   "（W-177）。HEAD の内容でテスト・比較したいなら "
                   "ops\\scripts\\run-against-head.ps1 を使うか、"
                   "`git show HEAD:<path>` を tmp\\ のファイルへ書き出して読むこと。"
                   "元に戻すのが目的なら、自分が書いた差分だけを Edit で戻す。")

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
