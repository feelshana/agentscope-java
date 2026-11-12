"""Exercise false-green failure modes using isolated Surefire fixtures."""

from pathlib import Path
import tempfile
import unittest

from dataagent_test_report import summarize


class TestReportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def write(self, name="restored", body="", failures=0, errors=0, skipped=0):
        (self.root / f"TEST-{name}.xml").write_text(
            f'<testsuite name="{name}" tests="1" failures="{failures}" '
            f'errors="{errors}" skipped="{skipped}"><testcase name="scenario">'
            f'{body}</testcase></testsuite>', encoding="utf-8")

    def test_missing_reports_and_missing_required_suite_fail(self):
        self.assertFalse(summarize(self.root, {"restored": 1})[1])
        self.write("other")
        self.assertFalse(summarize(self.root, {"restored": 1})[1])

    def test_restored_suite_must_run_enough_tests_without_skips(self):
        self.write()
        self.assertTrue(summarize(self.root, {"restored": 1})[1])
        self.assertFalse(summarize(self.root, {"restored": 2})[1])
        self.write(body='<skipped message="System property [live] does not exist"/>', skipped=1)
        self.assertFalse(summarize(self.root, {"restored": 1})[1])

    def test_optional_assumption_skip_is_visible_and_not_counted_as_pass(self):
        self.write()
        self.write("live", '<skipped type="org.opentest4j.TestAbortedException">'
                   'Assumption failed: no Wren fixture</skipped>', skipped=1)
        report, passed = summarize(self.root, {"restored": 1})
        self.assertTrue(passed)
        self.assertIn("| 2 | 0 | 0 | 1 |", report)
        self.assertIn("Conditional / assumption", report)
        self.assertIn("no Wren fixture", report)

    def test_disabled_and_unclassified_skips_fail_even_in_optional_suite(self):
        self.write()
        for reason in ["class is @Disabled", "No explanation"]:
            with self.subTest(reason=reason):
                self.write("optional", f'<skipped message="{reason}"/>', skipped=1)
                self.assertFalse(summarize(self.root, {"restored": 1})[1])

    def test_failures_errors_and_corrupt_reports_fail(self):
        for body, counters in [('<failure message="bad"/>', {"failures": 1}),
                               ('<error message="bad"/>', {"errors": 1})]:
            self.write(body=body, **counters)
            self.assertFalse(summarize(self.root, {"restored": 1})[1])
        (self.root / "TEST-restored.xml").write_text("broken", encoding="utf-8")
        self.assertFalse(summarize(self.root, {"restored": 1})[1])

    def test_counter_mismatch_duplicate_suite_and_markup_are_not_hidden(self):
        self.write(body='<skipped message="System property &lt;live&gt; | missing"/>', skipped=1)
        report, _ = summarize(self.root, {})
        self.assertIn("&lt;live&gt; &#124; missing", report)
        self.write()
        (self.root / "TEST-copy.xml").write_bytes((self.root / "TEST-restored.xml").read_bytes())
        self.assertFalse(summarize(self.root, {"restored": 1})[1])
        (self.root / "TEST-copy.xml").unlink()
        self.write(skipped=1)
        self.assertFalse(summarize(self.root, {"restored": 1})[1])


if __name__ == "__main__":
    unittest.main()
