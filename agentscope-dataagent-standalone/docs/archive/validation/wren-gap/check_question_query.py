"""Assert SSE tool ordering and query evidence, independent of streamed answer wording."""
import json
from pathlib import Path
out = Path(__file__).resolve().parent / "question-acceptance"
events = json.loads((out / "query.json").read_text(encoding="utf-8"))
calls = [e["toolName"] for e in events if e.get("type") == "tool_call"]
assert "wren_recall_examples" in calls and "wren_run_sql" in calls, calls
assert calls.index("wren_recall_examples") < calls.index("wren_run_sql"), calls
assert not any(e.get("type") == "error" for e in events), events[-5:]
results = [e for e in events if e.get("type") == "tool_result" and e.get("toolName") == "wren_run_sql"]
assert results and "3749" in str(results[-1]["toolResult"]), results
answer = "".join(e.get("data", "") for e in events if e.get("type") == "token")
assert "3749" in answer or "3,749" in answer, answer
summary = {"passed": True, "tools_in_order": calls,
           "result_yuan": 3749, "published_version": 2,
           "answer_mentions_result": True}
(out / "query-check.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
print(json.dumps(summary))
