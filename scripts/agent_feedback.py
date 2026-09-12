#!/usr/bin/env python3
"""Codex 훅과 수동 실행이 공유하는 파일·구조 검사. Python 표준 라이브러리만 사용한다."""

import argparse
import ast
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def digest(data):
    return hashlib.sha256(data).hexdigest()


class Feedback:
    def __init__(self, cwd, session):
        self.root = Path(subprocess.check_output(
            ["git", "rev-parse", "--show-toplevel"], cwd=cwd, text=True).strip())
        self.directory = self.root / "build/agent-feedback" / digest(session.encode())[:16]
        self.directory.mkdir(parents=True, exist_ok=True)
        self.state_file = self.directory / "state.json"

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root)

    def start(self, base=None):
        revision = self.git("rev-parse", "--verify", f"{base or 'HEAD'}^{{commit}}").decode().strip()
        return {"base": revision}

    def snapshot(self, state):
        paths = set()
        for args in (("diff", "--name-only", "-z", state["base"]),
                     ("diff", "--cached", "--name-only", "-z"),
                     ("ls-files", "--others", "--exclude-standard", "-z")):
            paths.update(os.fsdecode(p) for p in self.git(*args).split(b"\0") if p)
        files = {}
        for name in sorted(paths):
            path = self.root / name
            if path.is_symlink():
                files[name] = "link:" + digest(os.fsencode(os.readlink(path)))
            elif path.is_file():
                files[name] = digest(path.read_bytes()) + ":" + str(path.stat().st_mode)
            else:
                files[name] = "deleted"
        return {"files": files, "index": digest(self.git("diff", "--cached", "--binary"))}

    def command(self, name, command, accepted=(0,)):
        log = self.directory / f"{name}.log"
        with log.open("w") as output:
            try:
                result = subprocess.run(command, cwd=self.root, stdout=output,
                                        stderr=subprocess.STDOUT, timeout=180)
            except subprocess.TimeoutExpired:
                raise RuntimeError(f"{name}: 180초 초과. 로그: {log}") from None
        if result.returncode not in accepted:
            tail = log.read_text(errors="replace")[-3000:]
            raise RuntimeError(f"{name} 실패. 로그: {log}\n{tail}")

    def gradle(self, *tasks):
        self.command("gradle-" + "-".join(tasks),
                     ["./gradlew", "--console=plain", "--max-workers=2", *tasks])

    def files(self, state, snapshot):
        previous = state.get("local", {}).get("files", {})
        changed = {name for name in previous.keys() | snapshot["files"].keys()
                   if previous.get(name) != snapshot["files"].get(name)}
        self.command("diff-check", ["git", "diff", "--check", state["base"]])
        self.command("index-check", ["git", "diff", "--cached", "--check"])
        for name in sorted(changed):
            path = self.root / name
            if not path.is_file() or path.is_symlink():
                continue
            try:
                if path.suffix == ".json":
                    json.loads(path.read_text())
                elif path.suffix == ".py":
                    ast.parse(path.read_text(), filename=name)
                elif path.suffix == ".sh":
                    self.command("shell-syntax", ["bash", "-n", name])
            except (ValueError, SyntaxError) as error:
                raise RuntimeError(f"{name}: {error}") from None
        # --no-index의 정상 차이는 1, 공백 오류는 2번 비트로 구분한다.
        for raw in self.git("ls-files", "--others", "--exclude-standard", "-z").split(b"\0"):
            if raw and os.fsdecode(raw) in changed:
                self.command("untracked-check", ["git", "diff", "--no-index", "--check",
                                                  "--", "/dev/null", os.fsdecode(raw)], accepted=(0, 1))
        if any(name.endswith((".kt", ".kts", ".java")) or name.startswith("gradle/") or
               name in {"gradle.properties", "gradle.lockfile"} for name in changed):
            task = "testClasses" if any(name.startswith("src/test/") for name in changed) else "classes"
            self.gradle(task)
        state["local"] = snapshot

    def final(self, state, snapshot):
        # 작업 도중 커밋한 변경도 시작 리비전부터 검사한다. 스테이징·미추적 파일도 별도 포함한다.
        report = self.directory / "review.diff"
        with report.open("wb") as output:
            output.write(self.git("diff", "--no-ext-diff", "--no-color", state["base"], "--"))
            output.write(b"\n--- staged changes ---\n")
            output.write(self.git("diff", "--no-ext-diff", "--no-color", "--cached", "--"))
            output.flush()
            for raw in self.git("ls-files", "--others", "--exclude-standard", "-z").split(b"\0"):
                if raw:
                    result = subprocess.run(["git", "diff", "--no-index", "--no-ext-diff", "--no-color",
                                             "--", "/dev/null", os.fsdecode(raw)], cwd=self.root,
                                            stdout=output, stderr=subprocess.PIPE)
                    if result.returncode not in (0, 1):
                        raise RuntimeError(result.stderr.decode(errors="replace"))
        if snapshot["files"]:
            self.gradle("architectureTest", "feedbackLoopTest")
        return f"파일·구조 검사 통과. 전체 변경 검토: {report}"

    def run(self, phase, base=None, hook=False):
        with (self.directory / "lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            state = json.loads(self.state_file.read_text()) if self.state_file.exists() else self.start(base)
            if phase == "start":
                if not hook or state.get("completed"):
                    state = self.start(base)
                message = f"검사 기준: {state['base']}"
            else:
                snapshot = self.snapshot(state)
                if hook and snapshot == state.get("failed", {}).get("snapshot"):
                    if phase == "files":
                        return "", False
                    raise RuntimeError(state["failed"]["error"] +
                                       "\n같은 변경의 실패 결과입니다. 환경을 고쳤다면 final을 수동 재실행하세요.")
                if phase == "files" and snapshot == state.get("local"):
                    return "", False
                if hook and phase == "final" and snapshot == state.get("final"):
                    state["completed"] = True
                    message = "동일 변경의 검사 결과 재사용."
                else:
                    try:
                        self.files(state, snapshot)
                        message = "파일 검사 통과."
                        if phase == "final":
                            message = self.final(state, snapshot)
                            state["final"] = snapshot
                            state["completed"] = not hook or not snapshot["files"]
                        else:
                            state.pop("final", None)
                            state["completed"] = False
                        state.pop("failed", None)
                    except (OSError, ValueError, SyntaxError, RuntimeError, subprocess.CalledProcessError) as error:
                        state.pop("final", None)
                        state["failed"] = {"snapshot": snapshot, "error": str(error)}
                        self.state_file.write_text(json.dumps(state, ensure_ascii=False))
                        raise
            self.state_file.write_text(json.dumps(state, ensure_ascii=False))
            return message, hook and phase == "final" and not state.get("completed") and bool(snapshot["files"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("start", "files", "final", "hook"))
    parser.add_argument("--base", help="작업 시작 커밋. start에서만 사용")
    parser.add_argument("--session", default=os.environ.get("CODEX_THREAD_ID", "manual"))
    args = parser.parse_args()
    payload = json.load(sys.stdin) if args.phase == "hook" else {}
    event = payload.get("hook_event_name")
    phase = {"UserPromptSubmit": "start", "PostToolUse": "files", "Stop": "final"}.get(event, args.phase)
    try:
        feedback = Feedback(payload.get("cwd", os.getcwd()), payload.get("session_id", args.session))
        message, review = feedback.run(phase, args.base, hook=bool(payload))
        if payload:
            if review:
                print(json.dumps({"decision": "block", "reason": message +
                                 "\n보고서를 읽고 구조·책임 분리·누락을 검토한 뒤 종료하세요."}, ensure_ascii=False))
            elif message and phase == "files":
                print(json.dumps({"hookSpecificOutput": {"hookEventName": event,
                                  "additionalContext": message}}, ensure_ascii=False))
            else:
                print("{}")
        else:
            print(message or "파일 변경 없음: 검사 생략.")
    except (OSError, ValueError, SyntaxError, RuntimeError, subprocess.CalledProcessError) as error:
        message = f"검사 실패: {error}\n파일 수정은 이미 반영됐을 수 있습니다. 같은 편집을 반복하지 말고 원인을 수정하세요."
        if payload:
            print(json.dumps({"decision": "block", "reason": message}, ensure_ascii=False))
        else:
            print(message, file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
