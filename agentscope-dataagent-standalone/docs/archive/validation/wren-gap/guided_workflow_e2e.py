"""Verify guided modeling on an isolated server with real Wren and native HITL."""
import argparse
import csv
import json
import sys
import time
import urllib.parse
import uuid
from pathlib import Path
import live_validation as live

sys.stdout.reconfigure(encoding="utf-8")
live.BASE = "http://127.0.0.1:8085"
OUT = live.ROOT / "guided-workflow"
OUT.mkdir(exist_ok=True)
live.STATE = OUT / "state.json"
live.EVENTS = OUT / "events.jsonl"
state = live.load_state()
client = live.Client("bob")
parser = argparse.ArgumentParser()
parser.add_argument("phase", choices=["prepare", "model", "verify", "published", "expire", "query"])
args = parser.parse_args()

def snapshot(label):
    value = client.request("GET", live.modeling_path(state["group_id"]) + "/workflow")
    (OUT / (label + ".json")).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"step": label, "stage": value["stage"],
                      "queryAvailable": value["queryAvailable"],
                      "nextAction": value["nextAction"]["type"],
                      "canPublish": value["canPublish"]}, ensure_ascii=False), flush=True)
    return value

if args.phase == "prepare":
    group = client.request("POST", "/api/dataset-groups", {
        "name": "guided-workflow-" + uuid.uuid4().hex[:8],
        "description": "独立测试：引导建模与 Wren 实际验收"})
    state["group_id"] = group["id"]
    live.save_state(state)
    empty = snapshot("empty")
    assert empty["nextAction"]["type"] == "PREPARE_DATA" and not empty["queryAvailable"]
    foreign = live.Client("alice")
    try:
        foreign.request("GET", live.modeling_path(group["id"]) + "/workflow")
        raise AssertionError("Foreign owner could read workflow")
    except RuntimeError as error:
        assert "HTTP 403" in str(error) or "HTTP 404" in str(error)
    state["foreign_owner_rejected"] = True
    query = urllib.parse.urlencode({"groupId": group["id"], "name": "order"})
    state["dataset"] = client.request("POST", "/api/datasets?" + query,
        multipart=[("file", live.ROOT / "ds_order.csv")], timeout=300)
    live.save_state(state)
    for _ in range(30):
        base = snapshot("base")
        if base["queryAvailable"]:
            break
        time.sleep(2)
    assert base["queryAvailable"] and base["stage"] == "COMPLETE"
    assert base["questionSummary"]["total"] == 0 and base["publishedVersion"] == 1
elif args.phase == "model":
    gid = state["group_id"]
    state["session"] = "guided-model-" + uuid.uuid4().hex
    live.save_state(state)
    prompt = (
        "这是独立测试知识库。请先读取当前建模进度，再用真实逻辑模型名通过 write_file 保存"
        "唯一一个完整问题 knowledge/questions/total_sales.yml，并等待 HITL 确认。"
        "用户已经确认此问题为必验，required=true。问题：2025年有效订单销售额是多少？"
        "已确定口径：order_time 属于2025自然年，status 为 PAID 或 COMPLETED，is_deleted=0；"
        "amount_cents 除以100，单位元，不扣退款，不需要客户关联。无需再询问已明确内容。"
        "definition 与单条 SELECT SQL 一次写完整，无 LIMIT/OFFSET。仅登记需求，不修改其它模型，"
        "本轮不要执行工程校验、问题验证、业务确认或发布，保存后告诉用户去验证与确认。")
    events = client.stream("/api/agents/modeling-agent/chat/stream",
        {"message": prompt, "sessionKey": state["session"], "groupIds": [gid]}, "guided-model")
    hitl = 0
    for _ in range(6):
        requests = [e for e in events if e.get("type") == "hitl_request"]
        if not requests:
            break
        request = requests[-1]
        call = request["toolCalls"][0]
        data = call.get("input", {})
        assert call["name"] == "write_file" and data.get("path") == "knowledge/questions/total_sales.yml", call
        preview = client.request("POST", live.modeling_path(gid) + "/workspace/preview",
            {"toolName": call["name"], "input": data})
        assert preview["ok"], preview
        hitl += 1
        events = client.stream("/api/agents/modeling-agent/chat/confirm",
            {"sessionKey": state["session"], "groupIds": [gid], "replyId": request["replyId"],
             "toolCallId": call["id"], "toolName": call["name"], "confirmed": True}, "guided-model")
    assert hitl == 1, hitl
    reviews = client.request("GET", live.modeling_path(gid) + "/questions")
    assert len(reviews) == 1 and reviews[0]["question"]["sql"], reviews
    state["question_id"] = reviews[0]["question"]["id"]
    state["native_hitl_count"] = hitl
    live.save_state(state)
    before = snapshot("requirement_saved")
    assert before["queryAvailable"] and not before["canPublish"]
    assert before["stage"] == "VALIDATION", before
elif args.phase == "verify":
    gid = state["group_id"]
    base = live.modeling_path(gid)
    checked = client.request("POST", base + "/mdl/validate", timeout=300)
    assert checked["ok"], checked
    engineering = snapshot("engineering_pass")
    assert engineering["engineeringStatus"] == "PASSED" and not engineering["canPublish"]
    blocked = client.request("POST", base + "/mdl/publish", timeout=300)
    assert not blocked["ok"], blocked
    executed = client.request("POST", base + "/questions/total_sales/validate", timeout=300)
    assert executed["status"] == "EXECUTED", executed
    result = executed["validation"]["result"]
    rows = list(csv.DictReader((live.ROOT / "ds_order.csv").open(encoding="utf-8-sig")))
    oracle = sum(int(r["amount_cents"]) for r in rows if r["order_time"].startswith("2025-")
                 and r["status"] in {"PAID", "COMPLETED"} and r["is_deleted"] == "0") / 100
    assert result["rows"][0][result["columns"][0]] == oracle, result
    awaiting = snapshot("awaiting_confirmation")
    assert awaiting["stage"] == "CONFIRMATION" and not awaiting["canPublish"], awaiting
    state["oracle_yuan"] = oracle
    state["wren_result"] = result
    live.save_state(state)
elif args.phase == "published":
    gid = state["group_id"]
    final = snapshot("published")
    assert final["stage"] == "COMPLETE" and final["nextAction"]["type"] == "QUERY"
    browser = json.loads((OUT / "browser-checks.json").read_text(encoding="utf-8"))
    assert final["publishedVersion"] == browser.get("published_version", 2) and final["queryAvailable"] and not final["draftChanged"]
    state["published_by_browser"] = True
    live.save_state(state)
elif args.phase == "expire":
    # Mutate only this script's isolated fixture, never another group's artifacts.
    gid = state["group_id"]
    assert state.get("native_hitl_count") == 1 and state.get("published_by_browser")
    group_root = Path(__file__).resolve().parents[3] / ".tmp-question-acceptance/mdl" / gid
    suffix = "\n# Isolated workflow expiry fixture\n"
    for staged in (group_root / "project/models").glob("*/metadata.yml"):
        content = staged.read_text(encoding="utf-8")
        if content.endswith(suffix):
            staged.write_text(content[:-len(suffix)], encoding="utf-8")
    root = group_root / "workspace"
    model = root / "models/order/metadata.yml"
    if not model.exists():
        candidates = list((root / "models").glob("*/metadata.yml"))
        assert len(candidates) == 1
        model = candidates[0]
    model.write_text(model.read_text(encoding="utf-8") + suffix, encoding="utf-8")
    expired = snapshot("expired")
    assert expired["engineeringStatus"] == "NOT_CHECKED", expired
    assert expired["questionSummary"]["confirmed"] == 0 and expired["stage"] == "VALIDATION"
    assert expired["queryAvailable"] and expired["publishedVersion"] == 2 and not expired["canPublish"]
    reviews = client.request("GET", live.modeling_path(gid) + "/questions")
    assert reviews[0]["status"] == "STALE"
    state["expiry_checked"] = True
    live.save_state(state)
elif args.phase == "query":
    events = client.stream("/api/agents/data-agent/chat/stream", {
        "message": "请通过 Wren 实际查询2025年有效订单销售额，按已确认口径，以元展示。不要复用旧结果数值。",
        "sessionKey": "guided-query-" + uuid.uuid4().hex, "groupIds": [state["group_id"]]}, "guided-query")
    (OUT / "query.json").write_text(json.dumps(events, ensure_ascii=False, indent=2), encoding="utf-8")
    assert not any(e.get("type") == "error" for e in events), events[-3:]
    assert any(e.get("type") == "tool_call" and e.get("name", e.get("toolName")) == "wren_run_sql" for e in events), events
    print("Saved published-query SSE")
