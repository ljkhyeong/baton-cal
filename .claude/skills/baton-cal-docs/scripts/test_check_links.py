"""check_links.py 회귀 테스트. 실행: python3 -B -m unittest discover -s .claude/skills/baton-cal-docs/scripts"""

from pathlib import Path
import tempfile
import unittest

from check_links import check


class CheckLinksTest(unittest.TestCase):
    def test_reports_only_broken_targets_and_anchors(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / "guide.md").write_text("## OCI 이미지 검증\n", encoding="utf-8")
            page = root / "page.md"
            page.write_text("\n".join([
                "## Secret 파일 연결",
                "### `POST /internal/api/v1/items`",
                "## 중복",
                "## 중복",
                "[a](#secret-파일-연결) [b](#post-internalapiv1items) [c](#중복-1) [d](guide.md#oci-이미지-검증)",
                "[e](https://example.com) [f](/absolute/path.md) `[g](missing-in-code.md)`",
                "```",
                "[h](missing-in-fence.md)",
                "```",
                "[x](missing.md) [y](guide.md#없는-절) [z](#중복-2)",
                "[ref]: missing-ref.md",
            ]), encoding="utf-8")

            self.assertEqual(check(page, root), [
                "page.md:10: 대상 없음: missing.md",
                "page.md:10: 앵커 없음: guide.md#없는-절",
                "page.md:10: 앵커 없음: #중복-2",
                "page.md:11: 대상 없음: missing-ref.md",
            ])


if __name__ == "__main__":
    unittest.main()
