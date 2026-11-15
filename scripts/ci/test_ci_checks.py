"""Tests for discovering moved emulator suites and validating their coverage reports."""

# AI assistance: OpenAI Codex.
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


CI_SCRIPTS = Path(__file__).resolve().parent


class CiChecksTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def write(self, path, content):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content)
        return file

    def run_emulator_discovery(self):
        runner = self.write("scripts/ci/run-firebase-emulator-tests.sh", "")
        shutil.copyfile(CI_SCRIPTS / runner.name, runner)
        self.write("firebase.json", '{"firestore":{"rules":"firestore.rules"}}')
        self.write("firebase.firestore-open-test.json", "{}")
        self.write("firestore.rules", "")
        stub = self.write("bin/npx", '#!/bin/sh\nprintf "%s\\n" "$@"\n')
        stub.chmod(0o755)
        return subprocess.run(
            ["bash", str(runner)],
            env={**os.environ, "PATH": f"{stub.parent}:{os.environ['PATH']}"},
            capture_output=True, text=True, check=False,
        )

    def test_discovers_emulator_profiles_across_old_and_new_packages(self):
        for prefix, profile in (
            ("com.android.sample", "auth"),
            ("com.commando.app", "firestore.adapter"),
            ("com.commando.app", "firestore.security"),
        ):
            package = f"{prefix}.emulator.{profile}"
            self.write(f"app/src/test/java/{package.replace('.', '/')}/SuiteEmulatorTest.kt",
                       f"package {package}\n")

        result = self.run_emulator_discovery()
        self.assertEqual(result.returncode, 0, result.stderr)
        for profile in ("auth", "firestoreAdapter", "firestoreSecurity"):
            self.assertIn(f"--run-gradle-profile {profile}", result.stdout)

    def test_rejects_a_package_that_does_not_match_its_path(self):
        self.write("app/src/test/java/com/commando/app/emulator/auth/AuthEmulatorTest.kt",
                   "package com.android.sample.emulator.auth\n")

        result = self.run_emulator_discovery()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("must declare package com.commando.app.emulator.auth", result.stderr)

    def test_missing_test_root_fails_discovery(self):
        result = self.run_emulator_discovery()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("app/src/test", result.stderr)

    def test_coverage_requires_covered_main_source_under_the_reported_package(self):
        sources = self.root / "src"
        report = self.write("report.xml", """<report name="app">
          <package name="com/commando/app">
            <class name="com/commando/app/MainActivity" sourcefilename="MainActivity.kt"/>
            <sourcefile name="MainActivity.kt"><counter type="LINE" missed="0" covered="1"/></sourcefile>
          </package>
        </report>""")
        command = [sys.executable, str(CI_SCRIPTS / "validate-jacoco-report.py"),
                   str(report), str(sources)]

        self.write("src/com/android/sample/MainActivity.kt", "package com.android.sample\n")
        unrelated = subprocess.run(command, capture_output=True, text=True, check=False)
        self.assertNotEqual(unrelated.returncode, 0)

        self.write("src/com/commando/app/MainActivity.kt", "package com.commando.app\n")
        covered = subprocess.run(command, capture_output=True, text=True, check=False)
        self.assertEqual(covered.returncode, 0, covered.stderr)


if __name__ == "__main__":
    unittest.main()
