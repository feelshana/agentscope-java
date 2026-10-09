"""Run localhost E2E tests in a dedicated fixture knowledge base.

Secrets stay in memory. All confirmations are limited to test modeling operations.
"""
import argparse
from datetime import datetime
import json
from pathlib import Path
import time
import urllib.request
import urllib.error
import urllib.parse
import uuid

ROOT = Path(__file__).resolve().parent
STATE = ROOT / "live-state.json"
EVENTS = ROOT / "live-events.jsonl"
BASE = "http://127.0.0.1:8080"


def record(value):
    value = dict(timestamp=datetime.now().isoformat(), **value)
    with EVENTS.open("a", encoding="utf-8") as file:
        file.write(json.dumps(value, ensure_ascii=False) + "\n")
    print(json.dumps(value, ensure_ascii=False), flush=True)


def load_state():
    return json.loads(STATE.read_text(encoding="utf-8")) if STATE.exists() else {}


def save_state(state):
    STATE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


class Client:
    def __init__(self, user):
        self.token = None
        self.user = user
        body = self.request("POST", "/api/auth/login", {"username": user, "password": user})
        self.token = body["token"]

    def open(self, method, path, body=None, multipart=None, timeout=240):
        headers = {}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode()
            headers["Content-Type"] = "application/json"
        if multipart:
            boundary = "WrenValidation" + uuid.uuid4().hex
            parts = []
            for field, file_path in multipart:
                file_path = Path(file_path)
                parts.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; filename=\"{file_path.name}\"\r\nContent-Type: application/octet-stream\r\n\r\n").encode())
                parts.append(file_path.read_bytes())
                parts.append(b"\r\n")
            parts.append(f"--{boundary}--\r\n".encode())
            data = b"".join(parts)
            headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
        return urllib.request.urlopen(urllib.request.Request(BASE + path, data=data, headers=headers, method=method), timeout=timeout)

    def request(self, method, path, body=None, multipart=None, timeout=240):
        try:
            with self.open(method, path, body, multipart, timeout) as response:
                raw = response.read().decode("utf-8")
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8")
            raise RuntimeError(f"HTTP {error.code}: {raw[:1800]}") from None

    def stream(self, path, payload, label):
        collected = []
        with self.open("POST", path, payload, timeout=240) as response:
            data = []
            for raw in response:
                line = raw.decode("utf-8").rstrip("\r\n")
                if line.startswith("data:"):
                    data.append(line[5:].strip())
                elif not line and data:
                    content = "\n".join(data)
                    data = []
                    try:
                        event = json.loads(content)
                    except json.JSONDecodeError:
                        event = {"type": "raw", "data": content}
                    collected.append(event)
                    with (ROOT / f"live-{label}-sse.jsonl").open("a", encoding="utf-8") as log:
                        log.write(json.dumps(event, ensure_ascii=False) + "\n")
                    if event.get("type") in ("hitl_request", "done", "error"):
                        record({"stage": label, "event": event.get("type"), "sessionKey": event.get("sessionKey"), "error": event.get("error")})
        return collected


def group_path(group):
    return "/api/dataset-groups/" + urllib.parse.quote(group)


def modeling_path(group):
    return group_path(group) + "/modeling"


def prepare(client, state):
    if not state.get("group_id"):
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        group = client.request("POST", "/api/dataset-groups", {"name": "wren-gap-live-" + stamp, "description": "独立测试：语义建模、问数和Python验证；测试数据可保留复查"})
        state.update(group_id=group["id"], group_name=group["name"], uploaded=[])
        save_state(state)
        record({"stage": "prepare", "group_id": group["id"], "group_name": group["name"]})
    group = state["group_id"]
    names = ("customer", "order", "refund", "order_item", "product", "calendar_month", "event")
    for name in names:
        if name in state["uploaded"]:
            continue
        start = time.monotonic()
        query = urllib.parse.urlencode({"groupId": group, "name": name})
        result = client.request("POST", "/api/datasets?" + query, multipart=[("file", ROOT / f"ds_{name}.csv")], timeout=300)
        if isinstance(result, dict) and result.get("status") in ("PENDING", "RUNNING", "PROCESSING"):
            record({"stage": "upload", "name": name, "task": result})
            raise RuntimeError("Upload uses an asynchronous task; inspect task contract before continuing.")
        state["uploaded"].append(name)
        state.setdefault("datasets", {})[name] = result
        save_state(state)
        record({"stage": "upload", "name": name, "rows": result.get("rowCount"), "elapsed_seconds": round(time.monotonic() - start, 2)})
    knowledge = client.request("PUT", group_path(group) + "/knowledge", multipart=[("file", ROOT / "semantic-source.md")])
    record({"stage": "knowledge", "status": "saved"})
    for endpoint, filename in (("/modeling", "live-baseline-overview.json"), ("/modeling/mdl", "live-baseline-preview.json"), ("/modeling/mdl/view", "live-baseline-view.json")):
        result = client.request("GET", group_path(group) + endpoint)
        (ROOT / filename).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    record({"stage": "prepare", "status": "COMPLETE", "group_id": group})


ALLOWED = {"write_file", "patch_file", "decide_relation", "decide_relations", "add_relation", "create_view"}


def modeling(client, state, message=None):
    group = state["group_id"]
    session = state.setdefault("modeling_session", "validation-modeling-" + uuid.uuid4().hex)
    save_state(state)
    message = message or (
        "这是独立测试知识库，请按照本库已上传的semantic-source业务文档和Wren官方技能完成可发布的语义建模。"
        "先读取现状和业务文档，再提议并通过写工具确认。仅修改当前测试库，禁止创建或修改其他库和平台文件。"
        "请固化有效订单口径、元/分单位、付费客户去重；建立按客户与按月的有效订单指标；"
        "建立扣除成功退款的净营收视图，退款先按order_id汇总再关联订单，按原订单月份归属。"
        "记录空月份补齐规则和商品编码重复的数据质量问题，不要把重复product_code声明成唯一键。"
        "保留编码000123的字符串语义。结尾校验MDL并说明尚需页面发布。不要把预期答案当作查询依据。"
    )
    pending = client.request("GET", "/api/agents/modeling-agent/chat/session?" + urllib.parse.urlencode({"sessionKey": session}))
    if pending.get("pendingReplyId") and pending.get("pendingToolCalls"):
        events = [{"type":"hitl_request","replyId":pending["pendingReplyId"],"toolCalls":pending["pendingToolCalls"]}]
    else:
        events = client.stream("/api/agents/modeling-agent/chat/stream", {"message": message, "sessionKey": session, "groupIds": [group]}, "modeling")
    confirmations = 0
    while True:
        requests = [event for event in events if event.get("type") == "hitl_request"]
        if not requests:
            break
        hitl = requests[-1]
        calls = hitl.get("toolCalls", [])
        if not calls:
            raise RuntimeError("HITL without toolCalls")
        call = calls[0]
        tool_name = call["name"]
        tool_input = call.get("input", {})
        if tool_name not in ALLOWED:
            record({"stage":"modeling","status":"UNREVIEWED_TOOL","tool":tool_name})
            raise RuntimeError("Unreviewed tool cannot be automatically confirmed")
        if tool_name in ("write_file", "patch_file"):
            preview = client.request("POST", modeling_path(group) + "/workspace/preview", {"toolName":tool_name,"input":tool_input})
            record({"stage":"preview","tool":tool_name,"path":tool_input.get("path"),"ok":preview.get("ok"),"error":preview.get("error")})
        record({"stage":"confirm","tool":tool_name,"input":tool_input})
        events = client.stream("/api/agents/modeling-agent/chat/confirm", {"sessionKey":session,"groupIds":[group],
            "replyId":hitl["replyId"],"toolCallId":call["id"],"toolName":tool_name,"confirmed":True}, "modeling")
        confirmations += 1
        if confirmations >= 40:
            raise RuntimeError("Stopped at 40 confirmations")
    result = client.request("GET", modeling_path(group) + "/mdl/view")
    (ROOT / "live-modeled-view.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    record({"stage":"modeling","status":"ROUND_COMPLETE","confirmations":confirmations,
            "models":len(result.get("models",[])),"relations":len(result.get("relations",[])),"cubes":len(result.get("cubes",[])),"views":len(result.get("views",[]))})


def publish(client, state):
    base = modeling_path(state["group_id"])
    validation = client.request("POST", base + "/mdl/validate", timeout=300)
    (ROOT / "live-mdl-validation.json").write_text(json.dumps(validation,ensure_ascii=False,indent=2),encoding="utf-8")
    record({"stage":"validate","result":validation})
    if not validation.get("ok"):
        raise RuntimeError("MDL validation failed; publication was not attempted")
    result = client.request("POST", base + "/mdl/publish", timeout=300)
    (ROOT / "live-mdl-publish.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
    record({"stage":"publish","result":result})
    if not result.get("ok"):
        raise RuntimeError("Publication failed")
    state["published_version"] = result.get("mdlVersion")
    save_state(state)


def questions(client, state, identifiers=None):
    cases = json.loads((ROOT / "questions.json").read_text(encoding="utf-8"))
    if identifiers:
        cases = [case for case in cases if case["id"] in identifiers.split(",")]
    for case in cases:
        label = case["id"]
        start = time.monotonic()
        session = "validation-" + label + "-" + uuid.uuid4().hex
        try:
            events = client.stream("/api/agents/data-agent/chat/stream", {"message":case["question"] + " 请给出实际查询结果及使用口径，不能编造数据。", "sessionKey":session,"groupIds":[state["group_id"]]}, label)
            record({"stage":"question","id":label,"expected":case["expected"],
                    "elapsed_seconds":round(time.monotonic()-start,2),"event_count":len(events),
                    "tool_names":[e.get("toolName") for e in events if e.get("type")=="tool_call"],
                    "has_error":any(e.get("type")=="error" for e in events)})
        except Exception as error:
            record({"stage":"question","id":label,"status":"ERROR","error":str(error)})


def python_analysis(client,state):
    session = "validation-python-" + uuid.uuid4().hex
    message = (
        "请仅在当前测试知识库中使用Wren获取2025年1至4月有效订单月度营收，"
        "包含没有订单的月份为0，金额由分换算为元；然后用run_python读取查询产物文件进行分析，"
        "生成中文月度趋势图和CSV，报告总额、环比，并说明3月基数为0时4月环比不可计算。"
        "不得把结果行抄成Python字面量，不得拿截断明细样本代表总体。"
    )
    events = client.stream("/api/agents/data-agent/chat/stream", {"message":message,"sessionKey":session,
                            "groupIds":[state["group_id"]]}, "python")
    record({"stage":"python","tool_names":[e.get("toolName") for e in events if e.get("type")=="tool_call"],
            "has_error":any(e.get("type")=="error" for e in events),"event_count":len(events)})


def isolation(client,state):
    alice=Client("alice")
    tests=[]
    group=state["group_id"]
    paths=[group_path(group),modeling_path(group)+"/mdl/view",modeling_path(group)+"/workspace/file?path=relationships.yml"]
    for path in paths:
        try:
            alice.request("GET",path)
            tests.append({"path":path,"status":"FAIL","reason":"foreign user received content"})
        except Exception as error:
            tests.append({"path":path,"status":"PASS" if "HTTP 403" in str(error) or "HTTP 404" in str(error) else "ERROR","detail":str(error)})
    (ROOT/"live-isolation-results.json").write_text(json.dumps(tests,ensure_ascii=False,indent=2),encoding="utf-8")
    record({"stage":"isolation","results":tests})


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("phase",choices=("prepare","model","publish","questions","python","isolation"))
    parser.add_argument("--ids")
    parser.add_argument("--message")
    args=parser.parse_args()
    client=Client("bob")
    state=load_state()
    try:
        if args.phase=="prepare": prepare(client,state)
        elif args.phase=="model": modeling(client,state,args.message)
        elif args.phase=="publish": publish(client,state)
        elif args.phase=="questions": questions(client,state,args.ids)
        elif args.phase=="python": python_analysis(client,state)
        elif args.phase=="isolation": isolation(client,state)
    except Exception as error:
        record({"stage":args.phase,"status":"ERROR","error":str(error)})
        raise


if __name__=="__main__":
    main()
