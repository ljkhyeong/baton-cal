import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("agent_feedback", Path(__file__).parents[1] / "agent_feedback.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FeedbackTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="cal feedback ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "검증")
        (self.root / ".gitignore").write_text("build/\n")
        (self.root / "tracked.json").write_text("{}\n")
        self.git("add", ".")
        self.git("commit", "-qm", "초기 상태")
        self.feedback = module.Feedback(self.root, "test")
        self.feedback.run("start")

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, stderr=subprocess.PIPE)

    def state(self):
        return json.loads(self.feedback.state_file.read_text())

    def test_invalid_new_file_fails_then_recovers(self):
        path = self.root / "new file.json"
        path.write_text("{broken")
        with self.assertRaisesRegex(RuntimeError, "new file.json"):
            self.feedback.run("files")
        path.write_text("{}\n")
        self.feedback.run("files")

    def test_untracked_whitespace_is_checked(self):
        (self.root / "new file.md").write_text("공백 오류 \n")
        with self.assertRaisesRegex(RuntimeError, "untracked-check"):
            self.feedback.run("files")

    def test_unchanged_files_skip_gradle_and_deletions_compile(self):
        source = self.root / "src/main/kotlin/Example.kt"
        source.parent.mkdir(parents=True)
        source.write_text("class Example\n")
        with patch.object(self.feedback, "gradle") as gradle:
            self.feedback.run("files")
            self.feedback.run("files")
            gradle.assert_called_once_with("classes")
            source.unlink()
            self.feedback.run("files")
            self.assertEqual(gradle.call_count, 2)

    def test_test_changes_compile_java_and_kotlin_tests(self):
        source = self.root / "src/test/java/Example.java"
        source.parent.mkdir(parents=True)
        source.write_text("class Example {}\n")
        with patch.object(self.feedback, "gradle") as gradle:
            self.feedback.run("files")
            gradle.assert_called_once_with("testClasses")

    def test_final_includes_committed_staged_and_new_files(self):
        (self.root / "tracked.json").write_text('{"committed": true}\n')
        self.git("commit", "-qam", "작업 중 커밋")
        (self.root / "staged.json").write_text('{"staged": true}\n')
        self.git("add", "staged.json")
        (self.root / "new file.json").write_text('{"untracked": true}\n')
        with patch.object(self.feedback, "gradle") as gradle:
            self.feedback.run("final")
            gradle.assert_called_once_with("architectureTest", "feedbackLoopTest")
        report = (self.feedback.directory / "review.diff").read_text()
        for value in ("committed", "staged", "untracked"):
            self.assertIn(value, report)
        self.assertLess(report.index("committed"), report.index("--- staged changes ---"))
        self.assertLess(report.index("--- staged changes ---"), report.index("untracked"))

    def test_staged_error_cannot_hide_behind_clean_working_file(self):
        (self.root / "tracked.json").write_text("{} \n")
        self.git("add", "tracked.json")
        (self.root / "tracked.json").write_text("{}\n")
        with self.assertRaisesRegex(RuntimeError, "index-check"):
            self.feedback.run("files")

    def test_stop_requests_review_once_and_rechecks_after_edit(self):
        (self.root / "tracked.json").write_text('{"changed": true}\n')
        with patch.object(self.feedback, "gradle") as gradle:
            self.assertTrue(self.feedback.run("final", hook=True)[1])
            self.assertFalse(self.feedback.run("final", hook=True)[1])
            self.assertEqual(gradle.call_count, 1)
            (self.root / "tracked.json").write_text('{"changed": false}\n')
            self.assertTrue(self.feedback.run("final", hook=True)[1])
            self.assertEqual(gradle.call_count, 2)

    def test_failed_architecture_check_never_records_success(self):
        (self.root / "tracked.json").write_text('{"changed": true}\n')
        with patch.object(self.feedback, "gradle", side_effect=RuntimeError("계층 위반")):
            with self.assertRaisesRegex(RuntimeError, "계층 위반"):
                self.feedback.run("final", hook=True)
        self.assertNotIn("final", self.state())

    def test_unchanged_failure_does_not_repeat_and_manual_retry_recovers(self):
        (self.root / "tracked.json").write_text('{"changed": true}\n')
        with patch.object(self.feedback, "gradle", side_effect=RuntimeError("환경 오류")) as gradle:
            with self.assertRaises(RuntimeError):
                self.feedback.run("final", hook=True)
            self.assertEqual(self.feedback.run("files", hook=True), ("", False))
            with self.assertRaisesRegex(RuntimeError, "수동 재실행"):
                self.feedback.run("final", hook=True)
            self.assertEqual(gradle.call_count, 1)
        with patch.object(self.feedback, "gradle") as gradle:
            self.feedback.run("final")
            gradle.assert_called_once()
        self.assertNotIn("failed", self.state())

    def test_start_preserves_active_baseline_and_resolves_subdirectory(self):
        base = self.state()["base"]
        (self.root / "tracked.json").write_text('{"changed": true}\n')
        self.git("commit", "-qam", "작업 중 커밋")
        self.feedback.run("start", hook=True)
        self.assertEqual(self.state()["base"], base)
        (self.root / "subdir").mkdir()
        self.assertEqual(module.Feedback(self.root / "subdir", "other").root, self.root.resolve())

    def test_hook_returns_json_feedback_without_rejecting_completed_edit(self):
        (self.root / "broken.json").write_text("{broken")
        payload = {"cwd": str(self.root), "session_id": "hook test", "hook_event_name": "PostToolUse"}
        result = subprocess.run(["python3", "-B", str(Path(module.__file__)), "hook"],
                                input=json.dumps(payload), text=True, capture_output=True, cwd=self.root)
        self.assertEqual(result.returncode, 0)
        response = json.loads(result.stdout)
        self.assertEqual(response["decision"], "block")
        self.assertIn("broken.json", response["reason"])
        self.assertEqual((self.root / "broken.json").read_text(), "{broken")

    def script(self, *args, stdin=""):
        return subprocess.run(["python3", "-B", str(Path(module.__file__)), *args, "--session", "cli test"],
                              input=stdin, text=True, capture_output=True, cwd=self.root)

    def test_manual_failure_exits_nonzero_and_malformed_hook_input_blocks(self):
        self.assertEqual(self.script("start").returncode, 0)
        (self.root / "broken.json").write_text("{broken")
        result = self.script("files")
        self.assertEqual(result.returncode, 1)
        self.assertIn("broken.json", result.stderr)

        result = self.script("hook", stdin="{broken")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(json.loads(result.stdout)["decision"], "block")

    def test_manual_start_recovers_from_unreadable_state(self):
        self.feedback.state_file.write_text("{broken")
        with self.assertRaises(ValueError):
            self.feedback.run("files")
        self.feedback.run("start")
        self.assertIn("base", self.state())
        self.feedback.state_file.write_text("{}")
        result = subprocess.run(["python3", "-B", str(Path(module.__file__)), "files", "--session", "test"],
                                text=True, capture_output=True, cwd=self.root)
        self.assertEqual(result.returncode, 1)
        self.assertIn("검사 실패", result.stderr)

    def test_state_is_replaced_without_temporary_leftovers(self):
        self.feedback.run("start")
        self.assertEqual(sorted(path.name for path in self.feedback.directory.iterdir()), ["lock", "state.json"])

    def test_syntax_and_tracked_whitespace_errors_are_reported(self):
        cases = {"broken.py": ("def broken(:\n", "broken.py"), "broken.sh": ("if then\n", "shell-syntax")}
        for name, (content, message) in cases.items():
            with self.subTest(name=name):
                path = self.root / name
                path.write_text(content)
                with self.assertRaisesRegex(RuntimeError, message):
                    self.feedback.run("files")
                path.unlink()
        (self.root / "tracked.json").write_text("{} \n")
        with self.assertRaisesRegex(RuntimeError, "diff-check"):
            self.feedback.run("files")

    def test_slow_check_is_reported_as_failure(self):
        with patch.object(module.subprocess, "run", side_effect=subprocess.TimeoutExpired("git", 180)):
            with self.assertRaisesRegex(RuntimeError, "180초 초과"):
                self.feedback.command("slow", ["git", "status"])

    def test_gradle_settings_trigger_compilation(self):
        for name in ("build.gradle.kts", "gradle/verification-metadata.xml", "gradle.properties", "gradle.lockfile"):
            with self.subTest(name=name):
                path = self.root / name
                path.parent.mkdir(exist_ok=True)
                path.write_text("변경\n")
                with patch.object(self.feedback, "gradle") as gradle:
                    self.feedback.run("files")
                    gradle.assert_called_once_with("classes")

    def test_completed_final_restarts_baseline_on_next_prompt(self):
        (self.root / "tracked.json").write_text('{"changed": true}\n')
        self.git("commit", "-qam", "작업 커밋")
        with patch.object(self.feedback, "gradle"):
            self.feedback.run("final")
        self.feedback.run("start", hook=True)
        self.assertEqual(self.state()["base"], self.git("rev-parse", "HEAD").decode().strip())


if __name__ == "__main__":
    unittest.main()
