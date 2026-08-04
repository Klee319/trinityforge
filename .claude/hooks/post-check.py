"""PostToolUse check (TrinityForge project). K approved 2026-08-04.

編集直後に Phase 1 で実走検証済みのチェックだけを走らせ、失敗内容のみを
additionalContext で返す（成功時は何も出力しない）。

- TrinityForge/**/src/{main,test}/java/**/*.java
    -> gradlew compileJava compileTestJava --offline -q
       (JDK 21 の java.home 必須。取り違えると Gradle が 25.0.4 だけ吐いて落ちる)
- tools/config-editor/**/*.js
    -> node --check <file> (構文検査のみ。npm test は手動実行)

Fail-open: タイムアウト・予期しないエラーは何も出力せず exit 0。
worktree (.claude/worktrees/...) でも動くよう、対象ルートは編集ファイルから
上方向に探索して決める。
"""
import json
import os
import re
import subprocess
import sys

JAVA_HOME = r"C:\Program Files\Java\jdk-21"
TAIL_LINES = 40


def emit(context):
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PostToolUse",
            "additionalContext": context,
        }
    }))
    sys.exit(0)


def find_up(path, dirname, marker):
    """path の親を遡り、basename==dirname かつ marker を含むディレクトリを返す。"""
    d = os.path.dirname(os.path.abspath(path))
    for _ in range(30):
        if (os.path.basename(d).lower() == dirname.lower()
                and os.path.exists(os.path.join(d, marker))):
            return d
        nd = os.path.dirname(d)
        if nd == d:
            return None
        d = nd
    return None


def tail(text, n=TAIL_LINES):
    lines = [l for l in text.splitlines() if l.strip()]
    return "\n".join(lines[-n:])


def main():
    data = json.load(sys.stdin)
    if data.get("tool_name") not in ("Write", "Edit"):
        return
    fp = (data.get("tool_input") or {}).get("file_path") or ""
    if not fp or not os.path.exists(fp):
        return
    norm = fp.replace("\\", "/").lower()

    if norm.endswith(".java") and re.search(r"/src/(main|test)/java/", norm):
        root = find_up(fp, "TrinityForge", "gradlew.bat")
        if not root:
            return
        r = subprocess.run(
            ["cmd", "/c", os.path.join(root, "gradlew.bat"),
             "compileJava", "compileTestJava",
             "--offline", "-q", f"-Dorg.gradle.java.home={JAVA_HOME}"],
            cwd=root, capture_output=True, text=True, timeout=110,
            encoding="utf-8", errors="replace")
        if r.returncode != 0:
            emit("コンパイル失敗 (gradlew compileJava compileTestJava):\n"
                 + tail((r.stdout or "") + "\n" + (r.stderr or "")))
        return

    if norm.endswith(".js") and "/tools/config-editor/" in norm:
        r = subprocess.run(["node", "--check", fp],
                           capture_output=True, text=True, timeout=30,
                           encoding="utf-8", errors="replace")
        if r.returncode != 0:
            emit(f"構文エラー (node --check {os.path.basename(fp)}):\n"
                 + tail((r.stderr or "") + (r.stdout or "")))
        return


if __name__ == "__main__":
    try:
        main()
    except Exception:
        sys.exit(0)
    sys.exit(0)
