"""Assert the published query SSE against the independent CSV oracle and expired draft snapshot."""
import json
from pathlib import Path
import re
root = Path(__file__).resolve().parent / "guided-workflow"
state = json.loads((root / "state.json").read_text(encoding="utf-8"))
expired = json.loads((root / "expired.json").read_text(encoding="utf-8"))
events = json.loads((root / "query.json").read_text(encoding="utf-8"))
results = [e for e in events if e.get("type") == "tool_result" and e.get("toolName") == "wren_run_sql"]
assert results
text = str(results[-1]["toolResult"])
assert str(state["oracle_yuan"]).rstrip("0").rstrip(".") in text
assert state["group_id"] in text
assert "已发布 MDL 版本：" + str(expired["publishedVersion"]) in text
assert all(value in text for value in ["PAID", "COMPLETED", "is_deleted", "amount_cents"])
assert re.search(r"data/[a-zA-Z0-9_-]+\.csv", text)
checks = {"real_wren_query_matches_csv_oracle": True,
          "oracle_yuan": state["oracle_yuan"],
          "query_uses_published_version_with_expired_draft": expired["publishedVersion"],
          "group_scope_preserved": True, "csv_export_present": True,
          "query_tools": [e["toolName"] for e in events if e.get("type") == "tool_call"]}
(root / "query-checks.json").write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(checks, ensure_ascii=False))
