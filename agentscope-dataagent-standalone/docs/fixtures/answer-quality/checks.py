"""Deterministic answer checks; semantic definitions still need an independent human review."""
from decimal import Decimal, InvalidOperation
import re


def scalar(value):
    if value is None:
        return None
    text = str(value).strip().strip('`*').strip().replace(',', '').replace('，', '')
    if re.fullmatch(r'0\d+', text):
        return text
    try:
        return Decimal(text.rstrip('%'))
    except InvalidOperation:
        return text


def tables(answer):
    result, block = [], []
    for line in answer.splitlines() + ['']:
        if '|' in line:
            cells = [c.strip().strip('`*') for c in line.strip().strip('|').split('|')]
            if cells and not all(re.fullmatch(r'[:\s-]+', c or ' ') for c in cells):
                block.append(cells)
        elif block:
            if len(block) >= 2:
                result.append(block)
            block = []
    return result


def primary_rows(answer):
    """Use the primary result table, never correct numbers buried in an alternative paragraph."""
    found = tables(answer)
    if not found:
        return []
    table = found[0]
    if len(table[0]) >= 2 and '指标' in table[0][0] and any(
        word in table[0][1] for word in ['数值', '值']
    ):
        return [[row[1] for row in table[1:] if len(row) > 1]]
    return table[1:]


def primary_matches(answer, expected_rows, tolerance=Decimal('0.0051')):
    """Allow display rounding and column ordering, while preserving textual identifiers."""
    actual = primary_rows(answer)
    used_rows = set()

    def equals(a, b):
        a, b = scalar(a), scalar(b)
        if isinstance(a, Decimal) and isinstance(b, Decimal):
            return abs(a - b) <= tolerance
        return a == b

    def matches(row, candidate):
        choices = [[i for i, v in enumerate(candidate) if equals(e, v)] for e in row]

        def assign(index, used):
            if index == len(choices):
                return True
            return any(assign(index + 1, used | {i}) for i in choices[index] if i not in used)

        return all(choices) and assign(0, set())

    for row in expected_rows:
        candidates = [i for i, v in enumerate(actual) if i not in used_rows and matches(row, v)]
        if not candidates:
            return False
        used_rows.add(candidates[0])
    return bool(expected_rows)


def additive_total_errors(answer):
    """Validate explicit additive total rows with Decimal, excluding ratios and averages."""
    errors = []
    for table in tables(answer):
        for index, row in enumerate(table[1:], 1):
            if not row or not re.fullmatch(r'(?:合计|总计)(?:[（(].*)?', row[0]):
                continue
            for column, text in enumerate(row):
                if column >= len(table[0]) or any(
                    word in table[0][column] for word in ['率', '%', '平均', '人均', '占比']
                ):
                    continue
                total = scalar(text)
                values = [scalar(v[column]) for v in table[1:index] if len(v) > column]
                if not isinstance(total, Decimal) or not values or not all(
                    isinstance(v, Decimal) for v in values
                ):
                    continue
                expected = sum(values, Decimal(0))
                if abs(total - expected) >= Decimal('0.005'):
                    errors.append({'column': table[0][column], 'actual': str(total),
                                   'expected': str(expected)})
    return errors
