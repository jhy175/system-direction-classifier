"""Exercise model connections against a running MySQL application."""

import argparse
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import runpy
import secrets
from threading import Thread


ROOT = Path(__file__).resolve().parents[1]
IDENTITY = runpy.run_path(str(ROOT / "scripts/test-users.py"))
Client, check = IDENTITY["Client"], IDENTITY["check"]
API = "/model-connections"
HOST = "127.0.0.1"
PORT = 18764
PRIMARY = f"http://{HOST}:{PORT}/v1"
SECONDARY = f"http://{HOST}:{PORT}/backup"


@contextmanager
def provider():
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            header = self.headers.get("Authorization")
            requests.append((self.path, header, body))
            if self.path.endswith("/embeddings") and header is None:
                status, response = 200, {"model": body["model"], "data": [{"embedding": [0.2, 0.3, 0.4]}]}
            elif self.path.endswith("/chat/completions") and header in ("Bearer fixture-v1", "Bearer fixture-v2"):
                status, response = 200, {"model": body["model"], "choices": [{"message": {"content": "连接成功"}}]}
            else:
                status, response = 401, {"error": {"message": "Rejected header " + (header or "missing")}}
            data = json.dumps(response, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *args):
            pass

    with ThreadingHTTPServer((HOST, PORT), Handler) as server:
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield requests
        finally:
            server.shutdown()
            thread.join()


def login(base):
    settings = dict(line.split("=", 1) for line in
                    (ROOT / ".local/user-module.properties").read_text(encoding="utf-8-sig").splitlines() if "=" in line)
    client = Client(base)
    client.login(settings["adminLogin"], settings["adminPassword"])
    return client


def test(client, row, base_url=None, expected=200):
    payload = {"revisionId": row["revisionId"]}
    if base_url:
        payload["baseUrl"] = base_url
    return client.call("POST", f"{API}/{row['id']}/tests", payload, expected=expected)


def live_samples(admin):
    rows = admin.call("GET", API + "?search=qwen-plus")["items"]
    live = next(row for row in rows if row["name"] == "百炼 Qwen（可用样本）")
    invalid = next(row for row in rows if row["name"] == "百炼 Qwen（无效 Key 对照）")
    successful = test(admin, live)
    failed = test(admin, invalid)
    check("Imported AES-GCM credential works through Java", successful["callStatus"] == "success"
          and successful["httpStatus"] == 200 and successful["returnedModelId"] == "qwen-plus")
    check("Invalid sample records genuine authentication failure", failed["callStatus"] == "failed"
          and failed["httpStatus"] == 401 and failed["reachable"] and failed["errorCode"] == "auth_failed")
    options = {row["revisionId"] for row in admin.call("GET", API + "/options")}
    check("Only the healthy sample is selectable", live["revisionId"] in options and invalid["revisionId"] not in options)


def fixtures(base, admin, requests):
    suffix = secrets.token_hex(4)
    account = None
    team = None
    ids = []
    payload = {"name": "模型连接验收 " + suffix, "protocolCode": "openai_compatible", "providerCode": "vllm",
               "modelType": "chat", "baseUrl": PRIMARY, "modelId": "fixture-chat", "authType": "bearer",
               "visibility": "private", "enableThinking": False,
               "additionalEndpoints": [{"baseUrl": SECONDARY, "weight": 2}]}
    try:
        Client(base).call("GET", API, expected=401)
        admin.call("POST", API, {**payload, "baseUrl": "http://127.0.0.1:18765/v1"}, expected=400)
        admin.call("POST", API, {**payload, "apiKey": "****"}, expected=400)
        first = admin.call("POST", API, {**payload, "apiKey": "fixture-v1"})
        ids.append(first["id"])
        path = f"{API}/{first['id']}"
        check("New connection stores a credential without returning it",
              first["credentialConfigured"] and "fixture-v1" not in json.dumps(first))
        valid = test(admin, first)
        check("Chat test sends the configured payload and reports a real response",
              valid["callStatus"] == "success" and valid["httpStatus"] == 200
              and valid["responsePreview"] == "连接成功" and requests[-1][0] == "/v1/chat/completions"
              and requests[-1][2]["chat_template_kwargs"] == {"enable_thinking": False})
        alternate = test(admin, first, SECONDARY)
        check("Alternative endpoint gets its own test record", alternate["testedBaseUrl"] == SECONDARY
              and requests[-1][0] == "/backup/chat/completions")
        test(admin, first, "https://example.org/v1", expected=400)

        same = admin.call("PUT", path, {**payload, "description": "仅更新说明", "editToken": first["editToken"], "apiKey": ""})
        check("Empty API Key preserves credential, revision and latest test", same["credentialConfigured"]
              and same["revisionId"] == first["revisionId"] and same["lastCallStatus"] == "success")
        admin.call("PUT", path, {**payload, "editToken": first["editToken"]}, expected=409)
        rotated = admin.call("PUT", path, {**payload, "editToken": same["editToken"], "apiKey": "fixture-v2"})
        check("Key rotation keeps revision and resets latest test", rotated["revisionId"] == first["revisionId"]
              and rotated["lastCallStatus"] is None and len(rotated["tests"]) == 2)
        check("New credential is used on the next request", test(admin, rotated)["callStatus"] == "success"
              and requests[-1][1] == "Bearer fixture-v2")

        copied = admin.call("POST", path + "/copy", {"name": payload["name"] + " 副本"})
        ids.append(copied["id"])
        check("Copy preserves addresses and leaves the key unconfigured", not copied["credentialConfigured"]
              and copied["modelId"] == first["modelId"] and copied["visibility"] == "private")
        before = len(requests)
        check("An incomplete connection does not make a network request",
              test(admin, copied)["errorCode"] == "missing_credential" and len(requests) == before)
        disabled = admin.call("PUT", f"{API}/{copied['id']}/status",
                              {"status": "disabled", "editToken": copied["editToken"]})
        test(admin, disabled, expected=409)
        check("Server-side filters show the disabled copy",
              admin.call("GET", API + f"?search={suffix}&type=chat&status=disabled")["total"] == 1)

        new_model = admin.call("PUT", path, {**payload, "modelId": "fixture-chat-next", "editToken": rotated["editToken"]})
        check("Changing only the model creates a revision and preserves the credential", new_model["revisionNo"] == 2
              and new_model["credentialConfigured"] and new_model["lastCallStatus"] is None)
        changed = {**payload, "baseUrl": SECONDARY, "additionalEndpoints": []}
        new_address = admin.call("PUT", path, {**changed, "editToken": new_model["editToken"]})
        check("Changing the address scope leaves old revisions and isolates the credential",
              new_address["revisionNo"] == 3 and not new_address["credentialConfigured"]
              and new_address["revisions"][-1]["baseUrl"] == PRIMARY)

        embedding = admin.call("POST", API, {**payload, "name": payload["name"] + " 向量",
                               "modelId": "fixture-embedding", "modelType": "embedding", "authType": "none",
                               "enableThinking": None, "additionalEndpoints": []})
        ids.append(embedding["id"])
        result = test(admin, embedding)
        check("Embedding test verifies dimension without an Authorization header", result["callStatus"] == "success"
              and json.loads(result["usageJson"])["embedding_dimension"] == 3
              and requests[-1][0] == "/v1/embeddings" and requests[-1][1] is None)

        password = secrets.token_urlsafe(18)
        account = admin.call("POST", "/users", {"login": "model_it_" + suffix, "password": password, "status": 1})
        member = Client(base)
        member.login(account["login"], password)
        member.call("GET", path, expected=404)
        team = admin.call("POST", "/teams", {"name": "模型权限验收 " + suffix})
        admin.call("PUT", f"/teams/{team['id']}/members/{account['id']}", {})
        shared = admin.call("PUT", path, {**changed, "editToken": new_address["editToken"], "visibility": "team",
                                         "teamId": team["id"], "apiKey": "fixture-v2"})
        visible = member.call("GET", path)
        check("Team member can test without managing or reading credentials", visible["canTest"] and not visible["canManage"]
              and "fixture-v2" not in json.dumps(visible) and test(member, visible)["callStatus"] == "success")
        member.call("PUT", path + "/status", {"editToken": shared["editToken"], "status": "disabled"}, expected=403)
        admin.call("DELETE", f"/teams/{team['id']}/members/{account['id']}")
        member.call("GET", path, expected=404)
    finally:
        for connection in ids:
            path = f"{API}/{connection}"
            row = admin.call("GET", path)
            if row["status"] == "enabled":
                admin.call("PUT", path + "/status", {"status": "disabled", "editToken": row["editToken"]})
        if team:
            admin.call("DELETE", f"/teams/{team['id']}")
        if account:
            admin.call("PUT", f"/users/{account['id']}/disable", {})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live-samples", action="store_true", help="Retest the two existing samples in the business database.")
    args = parser.parse_args()
    base = "http://127.0.0.1:8082" if args.live_samples else "http://127.0.0.1:8083"
    admin = login(base)
    if args.live_samples:
        live_samples(admin)
    else:
        with provider() as requests:
            fixtures(base, admin, requests)
    print(json.dumps({"passed": len(IDENTITY["RESULTS"]), "liveSamples": args.live_samples}, ensure_ascii=False))


if __name__ == "__main__":
    main()
