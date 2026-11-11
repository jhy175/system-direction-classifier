"""Acceptance tests for the single-paper HTTP API and its MySQL-backed trace."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import runpy
import secrets
from threading import Thread
import time


ROOT = Path(__file__).resolve().parents[1]
identity = runpy.run_path(str(ROOT / "scripts/test-users.py"))
Client = identity["Client"]
PREFIX = "/debug"
STAGES = ["parse", "router", "recall", "judge", "post", "result"]
FIXTURE_URL = "http://127.0.0.1:18763/v1"


def expect(label, condition):
    if not condition:
        raise AssertionError(label)
    print("PASS", label, flush=True)


def terminal(client, run):
    deadline = time.monotonic() + 300
    while run["status"] in ("pending", "running"):
        if time.monotonic() >= deadline:
            raise AssertionError(f"Run {run['id']} did not finish. Stages: {run['stages']}")
        time.sleep(0.7)
        run = client.call("GET", f"{PREFIX}/runs/{run['id']}")
    return run


def stage(client, run, code, calls=False):
    return client.call("GET", f"{PREFIX}/runs/{run['id']}/stages/{code}?calls={str(calls).lower()}")


def request_for(method, suffix):
    return {"methodId": method["id"], "methodVersionId": method["defaultVersionId"], "input": {
        "type": "学术论文", "title": "城市热岛的遥感识别与绿地降温研究 " + suffix,
        "abstract": "本文利用多时相卫星遥感影像反演城市地表温度，结合土地覆盖、植被指数和建筑密度训练随机森林模型，比较不同城市功能区的热岛强度并分析绿地格局的降温效应。",
        "keywords": "城市热岛；遥感；地表温度；随机森林",
        "year": "202411",
    }}


class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        prompt = body["messages"][-1]["content"]
        code = 200
        if self.headers.get("Authorization") != "Bearer " + self.server.key:
            code, payload = 401, {"error": {"message": "wrong credential"}}
        elif "第一阶段：筛选相关学科簇" in prompt:
            if not self.server.router_failed:
                self.server.router_failed = True
                code, payload = 503, {"error": {"message": "temporary failure " + self.server.key}}
            else:
                clusters = self.items(prompt, "学科簇：")
                result = {"clusters": [{"cluster_id": item["cluster_id"], "relevance_level": "高度相关", "reason": "路由验收"}
                                       for item in clusters[:2]]}
        elif "第二阶段：从候选学科中筛选" in prompt:
            pool = self.items(prompt, "候选学科：")
            result = {"candidates": [
                {"category_id": row["category_id"], "relevance_level": "中度相关", "reason": "召回验收", "score": 1 - i / 10}
                for i, row in enumerate(pool[:4])
            ]}
        else:
            level = "格式无效" if self.server.invalid_judgment else "高度相关"
            result = {"results": [{"relevance_level": level, "reason": "独立精判验收", "confidence": "高",
                                    "matched_fields": ["研究方法"], "boundary_explanation": "边界验收"}]}
        if code == 200:
            payload = {"id": "recognition-fixture-call", "model": body["model"],
                       "choices": [{"message": {"content": json.dumps(result, ensure_ascii=False)}}],
                       "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120}}
        data = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    @staticmethod
    def items(prompt, label):
        text = prompt.split(label, 1)[1].lstrip()
        return json.JSONDecoder().raw_decode(text)[0]


def fixture_test(client, base, source):
    if base != "http://127.0.0.1:8083":
        raise ValueError("The fixture test must use the dedicated test application on port 8083")
    suffix = secrets.token_hex(4)
    model_path = None
    method_path = None
    with ThreadingHTTPServer(("127.0.0.1", 18763), Fixture) as provider:
        provider.key = "fixture-only-" + secrets.token_hex(20)
        provider.invalid_judgment = True
        provider.router_failed = False
        server = Thread(target=provider.serve_forever, daemon=True)
        server.start()
        try:
            model = client.call("POST", "/model-connections", {
                "name": "单篇调试验收 " + suffix, "protocolCode": "openai_compatible", "providerCode": "generic",
                "modelType": "chat", "baseUrl": FIXTURE_URL, "modelId": "recognition-fixture",
                "authType": "bearer", "apiKey": provider.key, "visibility": "private",
            })
            model_path = "/model-connections/" + str(model["id"])
            draft = client.call("POST", f"/methods/{source['id']}/versions/{source['defaultVersionId']}/copy",
                                {"name": "执行验收 " + suffix})
            method_path = "/methods/" + str(draft["id"])
            stages = [{"stageCode": name, "modelRevisionId": model["revisionId"],
                       "callOptions": {"llm_attempt_limit": 2, "candidate_attempt_limit": 2,
                                       "candidate_backoff_seconds_per_attempt": 0} if name == "judge" else {"llm_attempt_limit": 2}}
                      for name in ("router", "recall", "judge")]
            published = client.call("POST", method_path + "/draft/publish", {
                "draftId": draft["currentDraftId"], "revision": 0, "changeNote": "测试固定模型绑定",
                "stageChanges": stages + [{"stageCode": "post", "config": {"high_relevance_cap": 1}}],
            })
            method = published["method"]
            body = request_for(method, suffix)
            client.call("POST", PREFIX + "/runs", {**body, "input": {}}, expected=400)
            client.call("POST", PREFIX + "/runs", {**body, "methodVersionId": source["defaultVersionId"]}, expected=404)
            client.call("POST", PREFIX + "/runs", body, expected=403, csrf=False)
            failed = terminal(client, client.call("POST", PREFIX + "/runs", body))
            expect("Candidate failures remain failures and publish no result",
                   failed["status"] == "failed" and failed["failedStageCode"] == "judge" and failed["result"] is None)
            trace = stage(client, failed, "router", True)["calls"]
            expect("Router stores actual request, both HTTP attempts and the redacted response",
                   [row["status"] for row in trace] == ["failed", "succeeded"] and trace[0]["request"]["messages"]
                   and "[已脱敏]" in trace[0]["rawResponse"] and provider.key not in json.dumps(trace))
            judge = stage(client, failed, "judge", True)
            expect("Independent judge records each candidate's failed logical attempts",
                   judge["output"]["failed_count"] >= 2 and len(judge["calls"]) == judge["output"]["failed_count"] * 2)
            failed_path = f"{PREFIX}/runs/{failed['id']}"
            saved_failed = client.call("POST", failed_path + "/save")
            client.call("POST", failed_path + "/branch", {"mode": "retry", "stage": "router"}, expected=409)

            provider.invalid_judgment = False
            child = terminal(client, client.call("POST", failed_path + "/branch", {"mode": "retry", "stage": "judge"}))
            expect("Stage retry makes a new run and reuses the three upstream stages",
                   child["status"] == "succeeded" and child["parentRunId"] == failed["id"] and
                   [row["status"] for row in child["stages"][:3]] == ["reused"] * 3)
            expect("Retry reads the original router calls",
                   [row["id"] for row in stage(client, child, "router", True)["calls"]] == [row["id"] for row in trace])
            post = stage(client, child, "post")["output"]["complete_ranking"]
            expect("Saved method configuration controls post-processing",
                   sum(row["final_level"] == "高度相关" for row in post) == 1 and
                   any(row.get("adjustment") == "high_relevance_cap" for row in post))
            expect("Optional metric is missing and does not affect success", child["result"]["metrics"] == [])

            path = f"{PREFIX}/runs/{child['id']}"
            history = client.call("POST", path + "/save")
            expect("Repeated save pins the same result snapshot", client.call("POST", path + "/save")["id"] == history["id"])
            saved = client.call("GET", f"{PREFIX}/history/{history['id']}")
            failed_history = client.call("GET", f"{PREFIX}/history/{saved_failed['id']}")
            expect("History returns fixed result and failed snapshots",
                   saved["result"]["id"] == child["result"]["id"] and failed_history["status"] == "failed")
            expect("History query returns the persisted title", client.call("GET", f"{PREFIX}/history?search={suffix}")["total"] >= 2)

            seen = {row["discipline_id"] for row in post}
            manual = next(row for row in client.call("GET", path + "/disciplines") if row["id"] not in seen)
            branch = terminal(client, client.call("POST", path + "/branch", {"mode": "add_candidate", "disciplineId": manual["id"]}))
            expect("Adding a candidate creates a separately traceable branch",
                   branch["status"] == "succeeded" and branch["overrides"]["originalRunId"] == child["id"] and
                   any(row["candidate_source"] == "manual" and row["discipline_id"] == manual["id"]
                       for row in stage(client, branch, "judge")["output"]["judgments"]))
            resumed = terminal(client, client.call("POST", path + "/branch", {"mode": "resume", "stage": "post"}))
            expect("Resuming after judgment reuses evidence and publishes a separate result",
                   resumed["status"] == "succeeded" and resumed["result"]["id"] != child["result"]["id"] and
                   stage(client, resumed, "judge")["status"] == "reused")
            expect("All original history remains readable", client.call("GET", f"{PREFIX}/history/{history['id']}")["result"]["id"] == saved["result"]["id"])
        finally:
            if method_path:
                client.call("PUT", method_path + "/archive", {"archived": True})
            if model_path:
                model = client.call("GET", model_path)
                client.call("PUT", model_path + "/status", {"status": "disabled", "editToken": model["editToken"]})
            provider.shutdown()
            server.join()


def live_test(client, method):
    suffix = "真实模型验收-" + secrets.token_hex(3)
    run = terminal(client, client.call("POST", PREFIX + "/runs", request_for(method, suffix)))
    expect(f"Real model run {run['id']} completed all six stages",
           run["status"] == "succeeded" and all(row["status"] == "succeeded" for row in run["stages"]))
    expect("Real provider returned at least one classified discipline", bool(run["result"]["disciplines"]))
    for code in ("router", "recall", "judge"):
        calls = stage(client, run, code, True)["calls"]
        expect(f"{code} captured request, raw response and parsed output",
               any(row["request"] and row["rawResponse"] and row["parsedResponse"] and row["status"] == "succeeded"
                   for row in calls))
    history = client.call("POST", f"{PREFIX}/runs/{run['id']}/save")
    snapshot = client.call("GET", f"{PREFIX}/history/{history['id']}")
    expect(f"Real model history {history['id']} reopens the fixed result",
           snapshot["result"]["id"] == run["result"]["id"])
    print("Live main discipline:", run["result"]["disciplines"][0]["name"], flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", choices=("http://127.0.0.1:8083", "http://127.0.0.1:8082"),
                        default="http://127.0.0.1:8083")
    parser.add_argument("--live", action="store_true")
    args = parser.parse_args()
    config = dict(line.split("=", 1) for line in
                  (ROOT / ".local/user-module.properties").read_text(encoding="utf-8-sig").splitlines() if "=" in line)
    client = Client(args.base)
    Client(args.base).call("GET", PREFIX + "/history", expected=401)
    client.login(config["adminLogin"], config["adminPassword"])
    method = next(row for row in client.call("GET", "/methods?scope=builtin")["items"] if row["taxonomyCode"] == "moe")
    if args.live:
        live_test(client, method)
    else:
        fixture_test(client, args.base, method)


if __name__ == "__main__":
    main()
