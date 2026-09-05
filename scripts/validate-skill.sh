#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "사용법: $0 <스킬 디렉터리>" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
validator="${CODEX_HOME:-$HOME/.codex}/skills/.system/skill-creator/scripts/quick_validate.py"
if [[ ! -f "$validator" || ! -f "$1/SKILL.md" ]]; then
  echo "skill-creator 검증기와 대상 SKILL.md 경로를 확인하세요." >&2
  exit 2
fi

agent_env="$repo_root/.gradle/agent-validation"
if [[ ! -x "$agent_env/bin/python" ]]; then
  python3 -m venv "$agent_env"
fi
if ! "$agent_env/bin/python" -c 'import yaml' >/dev/null 2>&1; then
  echo "스킬 검증 의존성을 준비합니다."
  "$agent_env/bin/python" -m pip install --disable-pip-version-check 'PyYAML==6.0.3'
else
  echo "기존 스킬 검증 환경을 재사용합니다."
fi

exec "$agent_env/bin/python" "$validator" "$1"
