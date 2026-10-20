"""Keep launcher directories aligned with the packaged transcript appenders."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


class ReleaseLoggingTest(unittest.TestCase):
    def setUp(self):
        self.main = Path(__file__).resolve().parents[2] / "main"

    def test_launcher_prepares_logs_for_both_execution_paths(self):
        script = (self.main / "bin/dataagent.sh").read_text(encoding="utf-8")
        self.assertGreaterEqual(script.count('"$RELEASE_DIR/logs"'), 2)
        self.assertIn('"--dataagent.log-directory=$RELEASE_DIR/logs"', script)
        self.assertIn('"$RELEASE_DIR/logs/launcher.log"', script)
        self.assertNotRegex(script, r'\$RELEASE_DIR/log(?=["/])')
        self.assertNotIn(" log/launcher.log", script)

    def test_transcript_paths_and_separate_loggers_are_preserved(self):
        root = ET.parse(self.main / "resources/logback-spring.xml").getroot()
        for name, filename, logger in [
            ("LLM_FILE", "LLM", "LLM_DEBUG"),
            ("LLM_MODELING_FILE", "LLM-modeling", "LLM_MODELING_DEBUG"),
        ]:
            appender = root.find(f"appender[@name='{name}']")
            self.assertEqual(appender.findtext("file"), f"logs/{filename}.log")
            self.assertTrue(appender.findtext("rollingPolicy/fileNamePattern").startswith("logs/"))
            route = root.find(f"logger[@name='{logger}']")
            self.assertEqual(route.attrib["additivity"], "false")
            self.assertEqual(route.find("appender-ref").attrib["ref"], name)


if __name__ == "__main__":
    unittest.main()
