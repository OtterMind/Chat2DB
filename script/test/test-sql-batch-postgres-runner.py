import importlib.util
import io
from pathlib import Path
import tempfile
import sys
import unittest
from contextlib import redirect_stdout
from types import SimpleNamespace
from unittest.mock import patch

sys.dont_write_bytecode = True

SPEC = importlib.util.spec_from_file_location("sql_batch_runner", Path(__file__).with_name("run-sql-batch-postgres-it.py"))
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class RequiredReportTest(unittest.TestCase):
    def report(self, directory, name=None, tests=8, failures=0, errors=0, skipped=0):
        path = Path(directory) / "report.xml"
        path.write_text(f'<testsuite name="{name or RUNNER.SUITE}" tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}"/>', encoding="utf-8")
        return path

    def test_valid_report_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(8, RUNNER.verify_report(self.report(directory))["tests"])

    def test_missing_or_empty_report_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.xml"
            with self.assertRaises(Exception): RUNNER.verify_report(path)
            path.write_text("", encoding="utf-8")
            with self.assertRaises(Exception): RUNNER.verify_report(path)

    def test_zero_failed_error_skipped_and_wrong_suite_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            for change in ({"tests": 0}, {"failures": 1}, {"errors": 1}, {"skipped": 1}, {"name": "unrelated.Test"}):
                with self.subTest(change=change), self.assertRaises(RuntimeError):
                    RUNNER.verify_report(self.report(directory, **change))

    def test_foreign_container_is_not_removed(self):
        with patch.object(RUNNER, "docker", return_value="other-owner") as docker, redirect_stdout(io.StringIO()):
            self.assertEqual(1, RUNNER.cleanup_container("fixture-id", "expected-owner"))
            self.assertEqual(["inspect"], [call.args[0] for call in docker.call_args_list])

    def test_owned_container_is_removed(self):
        with patch.object(RUNNER, "docker", side_effect=["expected-owner", "fixture-id"]) as docker, redirect_stdout(io.StringIO()):
            self.assertEqual(0, RUNNER.cleanup_container("fixture-id", "expected-owner"))
            self.assertEqual(["inspect", "rm"], [call.args[0] for call in docker.call_args_list])

    def test_cleanup_failure_preserves_the_primary_maven_failure(self):
        def docker(command, *args, **kwargs):
            return {"run": "fixture-id", "port": "127.0.0.1:65432", "inspect": "other-owner"}[command]
        results = [SimpleNamespace(returncode=0), SimpleNamespace(returncode=17)]
        with patch.object(RUNNER, "docker", side_effect=docker), patch.object(RUNNER.subprocess, "run", side_effect=results), \
                patch.object(RUNNER, "REPORT", Path("missing-report.xml")), redirect_stdout(io.StringIO()):
            self.assertEqual(17, RUNNER.main())


if __name__ == "__main__": unittest.main()
