"""Validate the actual published example with the installed official Wren parser."""
import json
from pathlib import Path
from wren.memory.markdown import load_query_pairs

root = Path(__file__).resolve().parents[3]
out = Path(__file__).resolve().parent / "question-acceptance"
state = json.loads((out / "state.json").read_text(encoding="utf-8"))
project = root / ".tmp-question-acceptance/mdl" / state["group_id"] / "published"
pairs = load_query_pairs(project)
assert len(pairs) == 1, pairs
assert pairs[0]["nl"] == "2025年有效订单销售额是多少？"
assert 'FROM "order"' in pairs[0]["sql"]
assert pairs[0]["source"] == "user"
result = {"official_parser": "wren.memory.markdown.load_query_pairs",
          "pairs": len(pairs), "fields": sorted(pairs[0]), "passed": True}
(out / "official-parser-check.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
print(json.dumps(result))
