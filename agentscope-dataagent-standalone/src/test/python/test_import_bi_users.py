import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

SOURCE = Path(__file__).resolve().parents[3] / 'docs/integration/import-bi-users.py'
SPEC = importlib.util.spec_from_file_location('bi_users', SOURCE)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class BiUserImportTest(unittest.TestCase):
    def workbook(self, folder, formula=False):
        path = Path(folder) / 'users.xlsx'
        headers = ['loginname', 'name', 'company', 'dept', 'password']
        values = ['reader', '测试用户', '公司甲', '数据部', 'never-import-this']
        def row(index, items):
            return '<row>' + ''.join(
                f'<c r="{chr(65+i)}{index}" t="inlineStr"><is><t>{value}</t></is></c>'
                for i, value in enumerate(items)) + '</row>'
        sheet = ('<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'
                 + row(1, headers) + row(2, values)
                 + ('<row><c r="A3"><f>1+1</f><v>2</v></c></row>' if formula else '')
                 + '</sheetData></worksheet>')
        with zipfile.ZipFile(path, 'w') as book:
            book.writestr('xl/worksheets/sheet1.xml', sheet)
        return path

    def test_selects_only_profile_columns(self):
        with tempfile.TemporaryDirectory() as folder:
            result = MODULE.read_users(self.workbook(folder))
        self.assertEqual(result, [{'username': 'reader', 'displayName': '测试用户',
                                   'company': '公司甲', 'department': '数据部'}])
        self.assertNotIn('never-import-this', str(result))

    def test_refuses_formula_input(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, 'Formula'):
                MODULE.read_users(self.workbook(folder, formula=True))


if __name__ == '__main__':
    unittest.main()
