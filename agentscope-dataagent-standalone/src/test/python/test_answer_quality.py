import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).resolve().parents[3] / 'docs/fixtures/answer-quality/checks.py'
spec = importlib.util.spec_from_file_location('answer_checks', path)
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


class AnswerQualityTest(unittest.TestCase):
    def test_correct_alternative_numbers_do_not_rescue_wrong_primary_answer(self):
        answer = '| 指标 | 数值 |\n|---|---|\n| 完整次数 |8010|\n| 总次数 |20000|\n| 完播率 |40.05|\n\n按公式重算是5259、20000、26.30%。'
        self.assertFalse(checks.primary_matches(answer, [[5259, 20000, 26.30]]))

    def test_textual_identifiers_preserve_leading_zeros(self):
        self.assertFalse(checks.primary_matches('|编号|name|\n|---|---|\n|1|项目1|', [['000001', '项目1']]))
        self.assertTrue(checks.primary_matches('|编号|name|\n|---|---|\n|000001|项目1|', [['000001', '项目1']]))

    def test_money_totals_use_decimal_and_catch_one_cent_errors(self):
        answer = '|类别|成本|\n|---|---|\n|A|0.10|\n|B|0.20|\n|合计|0.29|'
        self.assertEqual(checks.additive_total_errors(answer)[0]['expected'], '0.30')
        self.assertEqual(checks.additive_total_errors(answer.replace('0.29', '0.30')), [])

    def test_ratio_totals_and_labels_containing_the_word_total_are_not_additive(self):
        answer = '|类别|占比(%)|\n|---|---|\n|A|33.33|\n|B|66.66|\n|合计|100.00|'
        self.assertEqual(checks.additive_total_errors(answer), [])
        self.assertEqual(checks.additive_total_errors('|指标|值|\n|---|---|\n|分子金额合计|100|\n|分母金额合计|300|'), [])


if __name__ == '__main__':
    unittest.main()
