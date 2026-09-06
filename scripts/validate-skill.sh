#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "사용법: $0 <스킬 디렉터리>" >&2
  exit 2
fi

skill_python="$HOME/.codex/venvs/skill-validation/bin/python"
validator="${CODEX_HOME:-$HOME/.codex}/skills/.system/skill-creator/scripts/quick_validate.py"
if [[ ! -f "$validator" || ! -f "$1/SKILL.md" ]]; then
  echo "skill-creator 검증기와 대상 SKILL.md 경로를 확인하세요." >&2
  exit 2
fi

exec "$skill_python" "$validator" "$1"
