"""Verify the new business-question flow against an isolated server and real Wren."""
import argparse
import csv
import json
import sys
import time
from pathlib import Path
import urllib.parse
import uuid
import live_validation as live

sys.stdout.reconfigure(encoding="utf-8")
live.BASE = "http://127.0.0.1:8085"
OUT = live.ROOT / "question-acceptance"
OUT.mkdir(exist_ok=True)
live.STATE = OUT / "state.json"
live.EVENTS = OUT / "events.jsonl"
state = live.load_state()
client = live.Client("bob")
phase = argparse.ArgumentParser()
phase.add_argument("phase", choices=["prepare", "model", "verify", "query"])
args = phase.parse_args()
if args.phase == "prepare":
    if not state.get("group_id"):
        group = client.request("POST", "/api/dataset-groups", {
            "name": "question-acceptance-" + uuid.uuid4().hex[:8],
            "description": "隔离测试：常用问题对话建模与 Wren 验收"})
        state["group_id"] = group["id"]
        live.save_state(state)
    if not state.get("dataset"):
        query = urllib.parse.urlencode({"groupId": state["group_id"], "name": "order"})
        state["dataset"] = client.request("POST", "/api/datasets?" + query,
            multipart=[("file", live.ROOT / "ds_order.csv")], timeout=300)
        live.save_state(state)
    print(json.dumps({"group_id": state["group_id"], "rows": state["dataset"].get("rowCount")}))
elif args.phase == "model":
    gid = state["group_id"]
    session = state.setdefault("modeling_session", "acceptance-model-" + uuid.uuid4().hex)
    live.save_state(state)
    message = (
        "这是独立测试知识库。请只创建一个常用问题文件 knowledge/questions/total_sales.yml，"
        "必须通过 write_file 并请求 HITL 确认。问题：2025年有效订单销售额是多少？"
        "业务口径：按 order_time 的2025自然年，status 为 PAID 或 COMPLETED 且 is_deleted=0，"
        "金额 amount_cents 除以100得到元，不扣退款，无需客户关联。required=true。"
        "先用 wren_context_show 或 list_modeling_state 读取真实逻辑模型名，然后写该问题的 definition 和 SQL。"
        "使用逻辑模型，单条 SELECT，无 LIMIT/OFFSET。写完调用 validate_modeling_question 执行验证，"
        "告诉用户去常用问题页面检查结果和确认。不要修改模型，不要替用户确认，不要发布。")
    events = client.stream("/api/agents/modeling-agent/chat/stream", {
        "message": message, "sessionKey": session, "groupIds": [gid]}, "question-model")
    for turn in range(8):
        requests = [e for e in events if e.get("type") == "hitl_request"]
        if not requests:
            break
        request = requests[-1]
        call = request["toolCalls"][0]
        data = call.get("input", {})
        if call["name"] != "write_file" or data.get("path") != "knowledge/questions/total_sales.yml":
            raise RuntimeError("Refused an unexpected modeling write")
        preview = client.request("POST", live.modeling_path(gid) + "/workspace/preview",
            {"toolName": call["name"], "input": data})
        assert preview.get("ok"), preview
        events = client.stream("/api/agents/modeling-agent/chat/confirm", {
            "sessionKey": session, "groupIds": [gid], "replyId": request["replyId"],
            "toolCallId": call["id"], "toolName": call["name"], "confirmed": True}, "question-model")
    print(json.dumps(client.request("GET", live.modeling_path(gid) + "/questions"), ensure_ascii=False))
elif args.phase == "verify":
    gid = state["group_id"]
    base = live.modeling_path(gid)
    checks = {}
    initial = client.request("GET", base + "/questions")
    assert initial and initial[0]["question"]["id"] == "total_sales", initial
    blocked = client.request("POST", base + "/mdl/publish", timeout=300)
    assert not blocked["ok"], blocked
    checks["unconfirmed_publish_blocked"] = True
    executed = client.request("POST", base + "/questions/total_sales/validate", timeout=300)
    (OUT / "execution.json").write_text(json.dumps(executed, ensure_ascii=False, indent=2), encoding="utf-8")
    assert executed["status"] == "EXECUTED", executed
    assert not executed["validation"]["truncated"], executed
    checks["real_wren_result"] = executed["validation"]["result"]
    rows = list(csv.DictReader((live.ROOT / "ds_order.csv").open(encoding="utf-8-sig")))
    oracle = sum(int(r["amount_cents"]) for r in rows if r["order_time"].startswith("2025-")
                 and r["status"] in {"PAID", "COMPLETED"} and r["is_deleted"] == "0") / 100
    result = executed["validation"]["result"]
    assert result["rows"][0][result["columns"][0]] == oracle, result
    checks["independent_csv_oracle_yuan"] = oracle
    validation_id = executed["validation"]["validationId"]
    try:
        client.request("POST", base + "/questions/total_sales/decision",
            {"validationId": "forged", "accepted": True})
        raise AssertionError("Forged validation was accepted")
    except RuntimeError as error:
        assert "HTTP 409" in str(error), str(error)
    checks["forged_receipt_rejected"] = True
    confirmed = client.request("POST", base + "/questions/total_sales/decision",
        {"validationId": validation_id, "accepted": True})
    assert confirmed["status"] == "CONFIRMED", confirmed
    published = client.request("POST", base + "/mdl/publish", timeout=300)
    assert published["ok"], published
    checks["confirmed_publish"] = published
    foreign = live.Client("alice")
    try:
        foreign.request("GET", base + "/questions")
        raise AssertionError("Foreign user read questions")
    except RuntimeError as error:
        assert "HTTP 404" in str(error) or "HTTP 403" in str(error), str(error)
    checks["foreign_owner_rejected"] = True
    for path in ["./wren_project.yml", "knowledge/questions/../sql/forged.md"]:
        preview = client.request("POST", base + "/workspace/preview", {
            "toolName": "write_file", "input": {"path": path, "content": "name: valid\n"}})
        assert not preview["ok"], preview
    checks["protected_preview_rejected"] = True
    (OUT / "checks.json").write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(checks, ensure_ascii=False))
elif args.phase == "query":
    events = client.stream("/api/agents/data-agent/chat/stream", {
        "message": "请先用 wren_recall_examples 查看已确认常用问题的口径，再通过 Wren 查询2025年有效订单销售额，以元展示。不要使用示例数值。",
        "sessionKey": "acceptance-query-" + uuid.uuid4().hex,
        "groupIds": [state["group_id"]]}, "question-query")
    (OUT / "query.json").write_text(json.dumps(events, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Saved query events")
