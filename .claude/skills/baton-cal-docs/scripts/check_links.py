#!/usr/bin/env python3
"""Markdown의 저장소 내부 링크와 GitHub 제목 앵커를 확인한다. Python 표준 라이브러리만 사용한다.

사용법: python3 -B check_links.py [파일 ...]  (생략하면 Git이 추적하는 모든 .md)
외부 URL과 절대 경로는 검사하지 않는다. 깨진 링크가 있으면 종료 코드 1을 반환한다.
"""

import functools
from pathlib import Path
import re
import subprocess
import sys

INLINE = re.compile(r"!?\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
REFERENCE = re.compile(r"^\s*\[[^\]]+\]:\s*<?(\S+?)>?(?:\s+\"[^\"]*\")?\s*$")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
CODE_SPAN = re.compile(r"`+[^`]*`+")


def visible_lines(path):
    """코드 블록 밖의 줄을 (줄 번호, 내용)으로 반환한다."""
    fence = None
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        marker = re.match(r"^\s*(```+|~~~+)", line)
        if marker:
            token = marker.group(1)[0] * 3
            fence = None if fence == token else fence or token
            continue
        if fence is None:
            yield number, line


def slug(text):
    # GitHub 규칙: 링크·코드 표시를 벗기고 소문자화, 문자·숫자·밑줄·하이픈·공백 외 제거, 공백은 하이픈으로 바꾼다.
    text = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", text).replace("`", "")
    return re.sub(r"[^\w\- ]", "", text.strip().lower()).replace(" ", "-")


@functools.cache
def anchors(path):
    seen, result = {}, set()
    for _, line in visible_lines(path):
        heading = HEADING.match(line)
        if heading:
            base = slug(heading.group(2))
            count = seen.get(base, 0)
            seen[base] = count + 1
            result.add(base if count == 0 else f"{base}-{count}")
    return frozenset(result)


def check(path, root):
    errors = []
    for number, line in visible_lines(path):
        targets = INLINE.findall(CODE_SPAN.sub("", line))
        reference = REFERENCE.match(line)
        if reference:
            targets.append(reference.group(1))
        for target in targets:
            if re.match(r"^[a-z][a-z0-9+.-]*:", target, re.I) or target.startswith("/"):
                continue
            name, _, anchor = target.partition("#")
            resolved = (path.parent / name).resolve() if name else path.resolve()
            if not resolved.exists() and re.search(r":\d+$", name):
                resolved = (path.parent / re.sub(r":\d+$", "", name)).resolve()
            where = f"{path.relative_to(root)}:{number}"
            if not resolved.exists():
                errors.append(f"{where}: 대상 없음: {target}")
            elif anchor and resolved.suffix == ".md" and anchor not in anchors(resolved):
                errors.append(f"{where}: 앵커 없음: {target}")
    return errors


def main():
    root = Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip()).resolve()
    files = [Path(name).resolve() for name in sys.argv[1:]] or [
        root / name for name in subprocess.check_output(
            ["git", "ls-files", "*.md"], cwd=root, text=True).splitlines()]
    files = [path for path in files if path.suffix == ".md"]
    errors = [error for path in files for error in check(path, root)]
    print("\n".join(errors) if errors else f"링크 검사 통과: Markdown {len(files)}개")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
