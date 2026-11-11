"""Run batch acceptance tests against MySQL through the real HTTP endpoints."""
import argparse
import base64
import csv
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
from pathlib import Path
import runpy
import secrets
from threading import Event, Thread
import time
from urllib.parse import urlencode
import zipfile

ROOT = Path(__file__).resolve().parents[1]
identity = runpy.run_path(str(ROOT / "scripts/test-users.py"))
Client = identity["Client"]
PREFIX = "/batch/tasks"
FIXTURE_URL = "http://127.0.0.1:18763/v1"
MAPPING = {"title": "title", "abstract": "abstract", "keywords": "keywords", "externalId": "id"}


def expect(label, condition):
    if not condition:
        raise AssertionError(label)
    print("PASS", label, flush=True)


def wait_task(client, task):
    deadline = time.monotonic() + 420
    while True:
        state = client.call("GET", f"{PREFIX}/{task}")
        if state["status"] in ("succeeded", "partial_failed", "failed"):
            return state
        if time.monotonic() >= deadline:
            raise AssertionError(f"Task {task} stayed {state['status']}; progress {state['progress']}")
        time.sleep(1)


def file_body(name, contents, **extra):
    return {"fileName": name, "content": base64.b64encode(contents).decode(), **extra}


def csv_bytes(rows, encoding="utf-8-sig"):
    text = io.StringIO()
    csv.writer(text).writerows([["group", "id", "title", "abstract", "keywords"], *rows])
    return text.getvalue().encode(encoding)


def xlsx_fixture():
    files = {
        "xl/workbook.xml": """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
            <sheets><sheet name="空表" r:id="rId1"/><sheet name="论文" r:id="rId2"/></sheets></workbook>""",
        "xl/_rels/workbook.xml.rels": """<Relationships>
            <Relationship Id="rId1" Target="worksheets/sheet1.xml"/>
            <Relationship Id="rId2" Target="/xl/worksheets/sheet2.xml"/></Relationships>""",
        "xl/worksheets/sheet1.xml": "<worksheet><sheetData/></worksheet>",
        "xl/worksheets/sheet2.xml": """<worksheet><sheetData><row r="1">
            <c r="A1" t="s"><v>0</v></c><c r="B1" t="inlineStr"><is><t>title</t></is></c></row>
            <row r="4"><c r="A4" t="inlineStr"><is><t>0004</t></is></c>
            <c r="B4" t="inlineStr"><is><r><t>城市</t></r><r><t>遥感</t></r></is></c></row></sheetData></worksheet>""",
        "xl/sharedStrings.xml": "<sst><si><t>id</t></si></sst>",
    }
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w", compression=zipfile.ZIP_DEFLATED) as workbook:
        for name, content in files.items():
            workbook.writestr(name, content.encode())
    return data.getvalue()


class Provider(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        prompt = body["messages"][-1]["content"]
        self.server.started.set()
        if not self.server.release.wait(25):
            self.send_error(503)
            return
        if "第一阶段：筛选相关学科簇" in prompt:
            rows = self.items(prompt, "学科簇：")
            answer = {"clusters": [{"cluster_id": row["cluster_id"], "relevance_level": "高度相关", "reason": "批量路由验收"}
                                    for row in rows[:1]]}
        elif "第二阶段：从候选学科中筛选" in prompt:
            rows = self.items(prompt, "候选学科：")
            answer = {"candidates": [{"category_id": row["category_id"], "relevance_level": "高度相关",
                                      "reason": "批量召回验收", "score": 0.85} for row in rows[:1]]}
        else:
            bad = self.server.fail_row and "BATCH-FAIL" in prompt
            answer = {"results": [{"relevance_level": "无效等级" if bad else "高度相关", "reason": "批量精判验收",
                                    "confidence": "高", "matched_fields": ["论文研究对象"]}]}
        payload = {"id": "batch-fixture", "model": body["model"],
                   "choices": [{"message": {"content": json.dumps(answer, ensure_ascii=False)}}]}
        data = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    @staticmethod
    def items(prompt, label):
        return json.JSONDecoder().raw_decode(prompt.split(label, 1)[1].lstrip())[0]


def create_task(client, method, name):
    return client.call("POST", PREFIX, {"name": name, "methodId": method["id"], "methodVersionId": method["defaultVersionId"]})


def load_item(client, task, item, run=None):
    return client.call("GET", f"{PREFIX}/{task}/items/{item}" + (f"?run={run}" if run else ""))


def load_stage(client, task, item, run, code):
    return client.call("GET", f"{PREFIX}/{task}/items/{item}/runs/{run}/stages/{code}?calls=true")


def export(client, task, kind, **extra):
    with client.http.open(client.base + "/api" + f"{PREFIX}/{task}/export?" + urlencode({"kind":kind, **extra}), timeout=25) as response:
        assert response.headers.get_content_type() == "text/csv"
        return list(csv.DictReader(io.StringIO(response.read().decode("utf-8-sig"))))


def lease_test(client, method, provider, suffix):
    database = runpy.run_path(str(ROOT/"scripts/import-knowledge.py"))
    task = create_task(client,method,"批量租约恢复验收 "+suffix)["id"]
    path = f"{PREFIX}/{task}"
    provider.started.clear()
    provider.release.clear()
    try:
        check = client.call("POST",path+"/imports",file_body("lease-test.csv",
            csv_bytes([["A","LEASE-01","城市遥感","识别城市土地覆盖","遥感"]]), mapping=MAPPING,businessKeyColumns=["id"]))
        client.call("POST",path+"/submit",{"importId":check["importId"],"submitKey":"lease-"+suffix},expected=202)
        expect("Background worker claims the lease independently",provider.started.wait(12))
        row = client.call("GET",path)["results"]["items"][0]
        expired_run = row["currentRunId"]
        with database["connect"]("direction_classifier_test") as connection, connection.cursor() as cursor:
            cursor.execute("UPDATE demo_batch_item SET lease_expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND "
                           "WHERE id=%s AND current_run_id=%s AND status='running'", (row["id"],expired_run))
            assert cursor.rowcount == 1
            connection.commit()
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            attempts = load_item(client,task,row["id"])["attempts"]
            if len(attempts) == 2:
                break
            time.sleep(0.3)
        expect("An expired claim is recovered with a new persisted run before the old response returns",
               len(attempts) == 2 and attempts[1]["id"] == expired_run and attempts[1]["errorCode"] == "worker_interrupted")
    finally:
        provider.release.set()
    final = wait_task(client,task)
    expect("Only the replacement run is adopted", final["status"] == "succeeded"
           and final["results"]["items"][0]["adoptedRunId"] != expired_run)
    stale = load_stage(client,task,row["id"],expired_run,"router")["calls"]
    expect("The late worker cannot publish its stale response",len(stale) == 1 and stale[0]["status"] == "unknown")


def fixture_test(client, builtin, check_lease=False):
    assert client.base == "http://127.0.0.1:8083", "Fixture test is only for the isolated test application"
    suffix = secrets.token_hex(4)
    model_path = method_path = guest_path = None
    with ThreadingHTTPServer(("127.0.0.1", 18763), Provider) as provider:
        provider.started = Event()
        provider.release = Event()
        provider.fail_row = True
        server = Thread(target=provider.serve_forever, daemon=True)
        server.start()
        try:
            model = client.call("POST", "/model-connections", {
                "name": "批量识别验收 " + suffix, "protocolCode": "openai_compatible", "providerCode": "generic",
                "modelType": "chat", "baseUrl": FIXTURE_URL, "modelId": "batch-fixture", "authType": "none", "visibility": "private",
            })
            model_path = "/model-connections/" + str(model["id"])
            draft = client.call("POST", f"/methods/{builtin['id']}/versions/{builtin['defaultVersionId']}/copy", {"name": "批量验收 " + suffix})
            method_path = "/methods/" + str(draft["id"])
            stages = [{"stageCode": name, "modelRevisionId": model["revisionId"],
                       "callOptions": {"llm_attempt_limit": 1, "candidate_attempt_limit": 1} if name == "judge" else {"llm_attempt_limit": 1}}
                      for name in ("router", "recall", "judge")]
            method = client.call("POST", method_path + "/draft/publish", {
                "draftId": draft["currentDraftId"], "revision": 0, "changeNote": "批量测试模型",
                "stageChanges": stages})["method"]
            task = create_task(client, method, "批量异步验收 " + suffix)["id"]
            path = f"{PREFIX}/{task}"
            xlsx = file_body("fixture.xlsx", xlsx_fixture())
            overview = client.call("POST", path + "/imports/inspect", xlsx)
            expect("XLSX exposes all worksheets including an empty first worksheet",
                   overview["sheets"] == ["空表", "论文"] and overview["total"] == 0)
            selected = client.call("POST", path + "/imports/inspect", {**xlsx,"sheet":"论文"})
            expect("XLSX reads the selected sheet, shared strings, rich text and original row position",
                   selected["columns"] == ["id","title"] and selected["rows"] == [{"sourceRowNo":4,"cells":["0004","城市遥感"]}])
            old_import = client.call("POST", path + "/imports", {**xlsx,"sheet":"论文",
                "mapping":{"title":"title","externalId":"id"},"businessKeyColumns":["id"]})
            expect("XLSX check persists independently", old_import["summary"]["valid"] == 1)

            chinese = csv_bytes([["A","0000","城市遥感","期刊摘要","遥感"]], "gb18030")
            client.call("POST", path + "/imports/inspect", file_body("charset.csv", chinese), expected=400)
            preview = client.call("POST", path + "/imports/inspect", file_body("charset.csv", chinese, encoding="GB18030"))
            expect("CSV encoding choice recovers original Chinese values", preview["rows"][0]["cells"][2] == "城市遥感")

            rows = [
                ["A","0001","城市热岛监测","多时相遥感，含引号 \"温度\"\n第二行","遥感"],
                ["B","0001","BATCH-FAIL","城市社会网络研究","社会网络"],
                ["C","0003","","",""],
                ["D","dup","重复之一","",""],
                ["D","dup","重复之二","",""],
            ]
            csv_file = file_body("fixture.csv", csv_bytes(rows), mapping=MAPPING, businessKeyColumns=["group","id"])
            checked = client.call("POST", path + "/imports", csv_file)
            expect("CSV rows retain multiline cells and original row position; composite keys preserve repeated external IDs",
                   checked["summary"] == {"total":5,"valid":2,"invalid":3}
                   and checked["rows"][1]["sourceRowNo"] == 4
                   and checked["rows"][0]["input"]["externalId"] == "0001"
                   and '\"温度\"\n第二行' in checked["rows"][0]["input"]["abstract"])
            expect("Draft invalid-row export uses persisted validation issues",
                   len(export(client, task, "invalid", importId=checked["importId"])) == 3)
            submit = {"importId":checked["importId"], "submitKey":suffix, "skipInvalid":False}
            client.call("POST", path + "/submit", submit, expected=409)
            client.call("POST", path + "/submit", submit, expected=403, csrf=False)
            queued = client.call("POST", path + "/submit", {**submit,"skipInvalid":True}, expected=202)
            expect("Submit returns while model calls are blocked and the work exists in the database queue",
                   queued["status"] in ("queued","running") and queued["progress"]["total"] == 2 and not provider.release.is_set())
            expect("A background worker reaches the provider independently of the HTTP request", provider.started.wait(12))
            again = client.call("POST", path + "/submit", {**submit,"skipInvalid":True}, expected=202)
            expect("Duplicate submit preserves task and item count", again["id"] == task and again["progress"]["total"] == 2)
            provider.release.set()
            first = wait_task(client, task)
            expect("Task reaches a partial failure from persisted per-paper outcomes",
                   first["status"] == "partial_failed" and first["progress"]["succeeded"] == 1 and first["progress"]["failed"] == 1)
            ok, bad = first["results"]["items"]
            result_run = ok["adoptedRunId"]
            failed_run = bad["currentRunId"]
            saved = load_item(client, task, ok["id"])
            expect("A batch row opens its adopted result revision and all six stages", saved["run"]["result"]["id"] == ok["adoptedResultId"]
                   and all(stage["status"] == "succeeded" for stage in saved["run"]["stages"]))
            for code in ("router","recall","judge"):
                calls = load_stage(client, task, ok["id"], result_run, code)["calls"]
                expect(f"Batch row retains complete {code} calls", bool(calls and calls[0]["request"] and calls[0]["rawResponse"] and calls[0]["parsedResponse"]))
            client.call("GET", f"{path}/items/{bad['id']}?run={result_run}", expected=404)
            client.call("GET", f"/debug/runs/{result_run}", expected=404)
            expect("Failed and skipped files use separate persistent data",
                   len(export(client,task,"failed")) == 1 and all(r["跳过"] == "是" for r in export(client,task,"invalid")))
            provider.fail_row = False
            client.call("POST", path + "/retry", expected=202)
            final = wait_task(client, task)
            expect("Retry creates an attempt only for the failed item",
                   final["status"] == "succeeded" and final["results"]["items"][0]["adoptedRunId"] == result_run
                   and final["results"]["items"][1]["adoptedRunId"] != failed_run)
            attempts = load_item(client, task, bad["id"])["attempts"]
            expect("Prior failed attempt remains visible", len(attempts) == 2 and attempts[1]["id"] == failed_run
                   and load_item(client,task,bad["id"],failed_run)["run"]["status"] == "failed")
            expect("Export pins each adopted result and leaves missing metric empty",
                   all(row["结果修订ID"] and row["交叉度"] == "" and row["指标状态"] == "not_provided"
                       for row in export(client,task,"results")))
            by_code = client.call("GET", path + "?" + urlencode({"discipline":ok["mainCode"],"status":"succeeded"}))
            expect("Server filters saved disciplines and statuses", by_code["results"]["total"] == 2)
            expect("Task list reads persisted tasks after a new GET", client.call("GET", PREFIX+"?search="+suffix)["total"] == 1)

            copied = client.call("POST", "/debug/runs", {"methodId":saved["run"]["methodId"],
                "methodVersionId":saved["run"]["methodVersionId"],"input":saved["run"]["input"],"sourceBatchRunId":result_run})
            expect("Copy to single debug creates a separate run with source lineage",
                   copied["id"] != result_run and copied["parentRunId"] == result_run and copied["triggerType"] == "copy_input")
            single = runpy.run_path(str(ROOT/"scripts/test-recognition.py"))["terminal"](client,copied)
            expect("Single debug still completes using the shared executor", single["status"] == "succeeded")

            role = next(role for role in client.call("GET", "/roles?pageSize=100")["items"] if role["code"] == "USER")
            password = "Batch-it-" + secrets.token_hex(14)
            guest = client.call("POST", "/users", {"login":"batch_it_"+suffix,"password":password,"status":1})
            guest_path = f"/users/{guest['id']}"
            client.call("PUT", guest_path+"/roles", [role["id"]])
            outsider = Client(client.base)
            outsider.login(guest["login"],password)
            outsider.call("GET", path, expected=404)
            outsider.call("GET", f"{path}/items/{ok['id']}", expected=404)
            outsider.call("GET", f"{path}/export?kind=results", expected=404)
            expect("Other users cannot open private tasks, items or exports", True)
            if check_lease:
                lease_test(client,method,provider,suffix)
        finally:
            provider.release.set()
            if guest_path:
                client.call("PATCH", guest_path, {"status":0})
            if method_path:
                client.call("PUT", method_path + "/archive", {"archived":True})
            if model_path:
                model = client.call("GET", model_path)
                client.call("PUT", model_path + "/status", {"status":"disabled","editToken":model["editToken"]})
            provider.shutdown()
            server.join()


def live_test(client, method):
    suffix = secrets.token_hex(3)
    task = create_task(client, method, "真实批量模型验收 " + suffix)["id"]
    path = f"{PREFIX}/{task}"
    rows = [
        ["A","LIVE-01","城市热岛的遥感识别与绿地降温效应",
         "本文基于多期卫星影像反演城市地表温度，结合土地覆盖和植被指数，比较城市功能区热岛强度并估计绿地格局的降温效应。","遥感；地表温度"],
        ["B","LIVE-02","面向图书馆馆藏的语义检索与知识组织",
         "本文对图书馆馆藏主题词和书目记录进行语义标注，构建馆藏知识组织体系，利用用户检索日志评估学术文献检索效果。","馆藏；知识组织；信息检索"],
    ]
    check = client.call("POST", path + "/imports", file_body("live-batch-verification.csv",csv_bytes(rows),
        mapping=MAPPING,businessKeyColumns=["id"]))
    queued = client.call("POST", path + "/submit", {"importId":check["importId"],"submitKey":suffix}, expected=202)
    print(f"Live task {task} submitted ({queued['status']}).", flush=True)
    final = wait_task(client, task)
    if final["status"] != "succeeded":
        raise AssertionError([(row["externalRecordId"],row["errorCode"],row["failedStageCode"]) for row in final["results"]["items"]])
    expect("Real batch completed two papers using the saved method", final["progress"]["succeeded"] == 2)
    for row in final["results"]["items"]:
        item = load_item(client, task, row["id"])
        run = item["run"]
        trace = load_stage(client,task,row["id"],run["id"],"judge")
        expect(f"{row['externalRecordId']} persisted its own result, phases and candidate calls",
               bool(run["result"]["id"] and len(run["stages"]) == 6 and trace["calls"]))
        print(row["externalRecordId"], "main discipline:", row["mainDiscipline"], flush=True)
    expect("Real result export contains both adopted revisions", len(export(client,task,"results")) == 2)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8083",
                        choices=("http://127.0.0.1:8083","http://127.0.0.1:8082"))
    parser.add_argument("--live", action="store_true")
    parser.add_argument("--check-lease", action="store_true", help="Exercise expired claim recovery against the test database (PyMySQL required)")
    args = parser.parse_args()
    settings = dict(line.split("=",1) for line in (ROOT/".local/user-module.properties").read_text(encoding="utf-8-sig").splitlines()
                    if "=" in line)
    client = Client(args.base)
    Client(args.base).call("GET", PREFIX, expected=401)
    client.login(settings["adminLogin"],settings["adminPassword"])
    source = next(row for row in client.call("GET","/methods?scope=builtin")["items"] if row["taxonomyCode"] == "moe")
    if args.live:
        live_test(client,source)
    else:
        fixture_test(client,source,args.check_lease)


if __name__ == "__main__":
    main()
