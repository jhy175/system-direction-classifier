"""Real HTTP/MySQL acceptance tests. Uses only Python's standard library.

Run against the dedicated test database on port 8083. Never resets or deletes a database.
Credentials are read from the ignored .local file, and never included in the report.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import concurrent.futures
import http.cookiejar
import json
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
RESULTS = []


def check(name, condition):
    if not condition:
        RESULTS.append({"name": name, "passed": False})
        raise AssertionError(name)
    RESULTS.append({"name": name, "passed": True})


class Client:
    def __init__(self, base):
        self.base = base
        self.jar = http.cookiejar.CookieJar()
        self.http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf = None
        self.last_headers = None

    def call(self, method, path, data=None, expected=200, csrf=True, headers=None):
        request_headers = {"Accept": "application/json"}
        if data is not None:
            request_headers["Content-Type"] = "application/json"
        if csrf and self.csrf:
            request_headers["X-CSRF-Token"] = self.csrf
        request_headers.update(headers or {})
        request = urllib.request.Request(
            self.base + "/api" + path,
            data=None if data is None else json.dumps(data, ensure_ascii=False).encode(),
            headers=request_headers, method=method)
        try:
            response = self.http.open(request, timeout=25)
        except urllib.error.HTTPError as error:
            response = error
        self.last_headers = response.headers
        status = response.code
        content = json.loads(response.read().decode("utf-8"))
        if status != expected:
            raise AssertionError(f"{method} {path}: expected {expected}, got {status}; message={content.get('message', '')}")
        check(f"HTTP {method} {path} -> {expected}", True)
        check(f"Envelope {method} {path}", content.get("success") is (expected < 400))
        return content.get("data")

    def login(self, login, password):
        self.csrf = self.call("GET", "/auth/csrf")["csrfToken"]
        result = self.call("POST", "/auth/login", {"login": login, "password": password})
        self.csrf = result["csrfToken"]
        return result["user"]


def run(base, persistence_only=False):
    settings = dict(line.split("=", 1) for line in
                    (ROOT / ".local/user-module.properties").read_text(encoding="utf-8-sig").splitlines() if "=" in line)
    admin = Client(base)
    root = admin.login(settings["adminLogin"], settings["adminPassword"])
    root_id = root["id"]
    me = admin.call("GET", "/auth/me")
    check("Password material never returned", "passwordHash" not in json.dumps(me) and "password_hash" not in json.dumps(me))
    if persistence_only:
        fixture = json.loads((ROOT / ".local/user-test-fixture.json").read_text())
        saved = admin.call("GET", "/users/" + str(fixture["userId"]))
        check("User survives application/database restart", saved["login"] == fixture["login"] and saved["displayName"] == "持久化验证用户")
        team = admin.call("GET", "/teams/" + str(fixture["teamId"]))
        check("Team and ownership survive restart", team["ownerUserId"] == fixture["userId"])
        check("Membership survives restart", any(m["userId"] == fixture["userId"] for m in admin.call("GET", f"/teams/{fixture['teamId']}/members")))
        return

    cookie = admin.last_headers.get_all("Set-Cookie") or []
    check("Session cookie is HttpOnly", all(c.has_nonstandard_attr("HttpOnly") for c in admin.jar))
    # Verify the actual login response, not only a configuration property.
    login_client = Client(base)
    login_client.login(settings["adminLogin"], settings["adminPassword"])
    cookie_header = ";".join(login_client.last_headers.get_all("Set-Cookie") or [])
    check("Session cookie is SameSite=Strict", "samesite=strict" in cookie_header.lower())
    anonymous = Client(base)
    anonymous.call("GET", "/users", expected=401)
    anonymous.call("POST", "/auth/login", {"login": "missing", "password": "bad"}, expected=403)
    anonymous.csrf = anonymous.call("GET", "/auth/csrf")["csrfToken"]
    anonymous.call("POST", "/auth/login", {"login": "missing", "password": "bad"}, expected=401)
    anonymous.call("POST", "/auth/login", {"login": "missing", "password": "bad"}, expected=403, headers={"Origin": "https://external.example"})
    admin.call("POST", "/users", {"login": "ignored"}, expected=403, csrf=False)

    suffix = secrets.token_hex(4)
    password = "Test-" + secrets.token_hex(12)
    created = []

    def user(label, status=1):
        row = admin.call("POST", "/users", {"login": f"it_{label}_{suffix}", "password": password, "status": status})
        created.append(row)
        return row

    owner, member, outsider, teammate_admin = [user(n) for n in ["owner", "member", "outsider", "manager"]]
    owner_client, member_client, outsider_client, manager_client = [Client(base) for _ in range(4)]
    for client, row in zip([owner_client, member_client, outsider_client, manager_client], [owner, member, outsider, teammate_admin]):
        client.login(row["login"], password)
    uid = owner["id"]
    minimal = admin.call("POST", "/users", {"login": f"it_min_{suffix}"})
    minimal2 = admin.call("POST", "/users", {"login": f"it_min2_{suffix}", "email": ""})
    check("Optional user columns accept NULL", minimal["email"] is None and minimal["displayName"] is None and minimal2["email"] is None)
    check("New accounts default to disabled", minimal["status"] == 0)
    check("No password hash exposed on create", "passwordHash" not in minimal)
    admin.call("PUT", f"/users/{minimal['id']}/activate", {})
    no_password = Client(base)
    no_password.csrf = no_password.call("GET", "/auth/csrf")["csrfToken"]
    no_password.call("POST", "/auth/login", {"login": minimal["login"], "password": ""}, expected=401)
    admin.call("POST", "/users", {"login": f"  IT_OWNER_{suffix.upper()}  "}, expected=409)
    for payload in [{"login": " "}, {"login": None}, {"login": "x" * 65}, {"login": "contains space"}, {"login": "valid", "unknown": "x"}]:
        admin.call("POST", "/users", payload, expected=400)
    admin.call("PATCH", f"/users/{uid}", {"email": "bad-email"}, expected=400)
    admin.call("PATCH", f"/users/{uid}", {"status": True}, expected=400)
    admin.call("PATCH", f"/users/{uid}", {"status": 999999999999999}, expected=400)
    admin.call("GET", "/users?pageSize=100000", expected=400)
    admin.call("GET", "/users/not-a-number", expected=400)
    admin.call("GET", "/users/9223372036854775807", expected=404)
    admin.call("PATCH", f"/users/{uid}", {"email": f"mail_{suffix}@example.test"})
    admin.call("PATCH", f"/users/{member['id']}", {"email": f"mail_{suffix}@example.test"}, expected=409)
    owner_client.call("PATCH", "/auth/me", {"displayName": "测试用户", "email": None, "department": "测试部门", "preferences": {"advancedConfig": True}})
    profile = owner_client.call("PATCH", "/auth/me", {"department": None})
    check("PATCH distinguishes omitted and explicit NULL", profile["department"] is None and profile["displayName"] == "测试用户")
    check("Preferences persist as JSON", profile["preferences"]["advancedConfig"] is True)
    owner_client.call("PATCH", "/auth/me", {"status": 1}, expected=400)
    owner_client.call("GET", "/users", expected=403)
    owner_client.call("PATCH", f"/users/{member['id']}", {"displayName": "forbidden"}, expected=403)
    owner_client.call("GET", f"/users/{member['id']}", expected=403)

    roles = admin.call("GET", "/roles?pageSize=100")["items"]
    admin_role = next(r for r in roles if r["code"] == "ADMIN")
    user_role = next(r for r in roles if r["code"] == "USER")
    admin.call("PUT", f"/users/{root_id}/disable", {}, expected=409)
    admin.call("PATCH", f"/users/{root_id}", {"status": None}, expected=409)
    admin.call("PUT", f"/users/{root_id}/roles", [], expected=409)
    admin.call("PUT", f"/users/{root_id}/roles", [1.5], expected=400)
    admin.call("PUT", f"/users/{root_id}/roles", [None], expected=400)
    check("Rejected role replacement rolls back", any(r["code"] == "ADMIN" for r in admin.call("GET", f"/users/{root_id}/roles")))
    admin.call("DELETE", f"/roles/{admin_role['id']}", expected=409)
    admin.call("PUT", f"/roles/{admin_role['id']}/disable", {}, expected=409)
    admin.call("PUT", f"/roles/{admin_role['id']}/permissions", [], expected=409)

    custom = admin.call("POST", "/roles", {"code": "IT_" + suffix.upper(), "name": "临时授权角色", "description": None, "sort": None})
    permissions = admin.call("GET", "/permissions?pageSize=100")["items"]
    manage_users = next(p for p in permissions if p["code"] == "user:manage")
    admin.call("PATCH", f"/permissions/{manage_users['id']}", {"status": None}, expected=409)
    admin.call("PUT", f"/roles/{custom['id']}/permissions", [manage_users["id"], manage_users["id"]])
    admin.call("PUT", f"/users/{uid}/roles", [user_role["id"], custom["id"]])
    owner_client.call("GET", "/users")
    admin.call("PUT", f"/roles/{custom['id']}/permissions", [999999999], expected=404)
    check("Invalid relation assignment is atomic", len(admin.call("GET", f"/roles/{custom['id']}/permissions")) == 1)
    admin.call("PATCH", f"/roles/{custom['id']}", {"status": None})
    owner_client.call("GET", "/users", expected=403)
    admin.call("PUT", f"/roles/{custom['id']}/enable", {})
    owner_client.call("GET", "/users")
    admin.call("PUT", f"/roles/{custom['id']}/permissions", [])
    owner_client.call("GET", "/users", expected=403)
    assigned_menu = admin.call("POST", "/menus", {"name": "授权菜单", "path": "/assigned/" + suffix})
    admin.call("PUT", f"/roles/{custom['id']}/menus", [assigned_menu["id"]])
    check("Role menu assignment is visible", any(m["id"] == assigned_menu["id"] for m in owner_client.call("GET", f"/users/{uid}/menus")))
    admin.call("PUT", f"/menus/{assigned_menu['id']}/hide", {})
    check("Hidden menu immediately disappears", all(m["id"] != assigned_menu["id"] for m in owner_client.call("GET", f"/users/{uid}/menus")))
    admin.call("PUT", f"/menus/{assigned_menu['id']}/show", {})
    admin.call("PUT", f"/roles/{custom['id']}/menus", [])
    check("Menu revocation immediately applies", all(m["id"] != assigned_menu["id"] for m in owner_client.call("GET", f"/users/{uid}/menus")))
    admin.call("DELETE", f"/menus/{assigned_menu['id']}")
    admin.call("DELETE", f"/roles/{custom['id']}")
    check("Role deletion cleans associations", all(r["id"] != custom["id"] for r in admin.call("GET", f"/users/{uid}/roles")))

    parent = admin.call("POST", "/menus", {"name": "测试目录", "path": None, "sort": None})
    child = admin.call("POST", "/menus", {"name": "测试子菜单", "parentId": parent["id"], "path": "/it/" + suffix})
    admin.call("PATCH", f"/menus/{parent['id']}", {"parentId": child["id"]}, expected=409)
    admin.call("PATCH", f"/menus/{child['id']}", {"parentId": child["id"]}, expected=409)
    admin.call("DELETE", f"/menus/{parent['id']}", expected=409)
    admin.call("PATCH", f"/menus/{child['id']}", {"parentId": 99999999}, expected=404)
    admin.call("GET", "/menus/tree")
    admin.call("PUT", f"/menus/{child['id']}/hide", {})
    admin.call("PUT", f"/menus/{child['id']}/show", {})
    admin.call("PATCH", f"/menus/{child['id']}", {"parentId": None, "path": None})
    admin.call("DELETE", f"/menus/{parent['id']}")
    admin.call("DELETE", f"/menus/{child['id']}")
    permission = admin.call("POST", "/permissions", {"code": "it:" + suffix, "name": "测试权限", "type": None})
    permission_child = admin.call("POST", "/permissions", {"code": "it:child:" + suffix, "name": "子权限", "parentId": permission["id"]})
    admin.call("PATCH", f"/permissions/{permission['id']}", {"parentId": permission_child["id"]}, expected=409)
    admin.call("GET", "/permissions/tree")
    admin.call("DELETE", f"/permissions/{permission_child['id']}")
    admin.call("DELETE", f"/permissions/{permission['id']}")

    team = owner_client.call("POST", "/teams", {"name": "集成测试团队 " + suffix})
    tid = team["id"]
    check("Team creation atomically creates owner membership", len(owner_client.call("GET", f"/teams/{tid}/members")) == 1)
    outsider_client.call("GET", f"/teams/{tid}", expected=403)
    outsider_client.call("GET", f"/teams/{tid}/members", expected=403)
    check("Other teams hidden from ordinary list", all(t["id"] != tid for t in outsider_client.call("GET", "/teams")["items"]))
    owner_client.call("PUT", f"/teams/{tid}/members/{member['id']}", {})
    member_client.call("GET", f"/teams/{tid}")
    member_client.call("PATCH", f"/teams/{tid}", {"description": "forbidden"}, expected=403)
    member_client.call("PUT", f"/teams/{tid}/members/{member['id']}", {"memberRole": "admin"}, expected=403)
    owner_client.call("PUT", f"/teams/{tid}/owner", {"userId": outsider["id"]}, expected=409)
    owner_client.call("PATCH", f"/teams/{tid}", {"ownerUserId": member["id"]}, expected=400)
    owner_client.call("DELETE", f"/teams/{tid}/members/{uid}", expected=409)
    admin.call("PUT", f"/users/{uid}/disable", {}, expected=409)
    admin.call("PATCH", f"/users/{uid}", {"status": None}, expected=409)
    owner_client.call("PUT", f"/teams/{tid}/members/{teammate_admin['id']}", {"memberRole": "admin"})
    manager_client.call("PUT", f"/teams/{tid}/members/{outsider['id']}", {})
    manager_client.call("PUT", f"/teams/{tid}/members/{outsider['id']}", {"memberRole": "admin"}, expected=403)
    manager_client.call("DELETE", f"/teams/{tid}/members/{uid}", expected=409)
    owner_client.call("PUT", f"/teams/{tid}/members/{outsider['id']}", {"memberRole": "admin"})
    owner_client.call("DELETE", f"/teams/{tid}/members/{outsider['id']}")
    check("Removed member immediately loses access", True)
    outsider_client.call("GET", f"/teams/{tid}", expected=403)
    rejoined = owner_client.call("PUT", f"/teams/{tid}/members/{outsider['id']}", {"status": 1})
    check("Rejoin does not restore previous administrator role", rejoined["memberRole"] == "member" and rejoined["leftAt"] is None)
    owner_client.call("PUT", f"/teams/{tid}/members/{outsider['id']}", {"status": None})
    outsider_client.call("GET", f"/teams/{tid}", expected=403)
    owner_client.call("PUT", f"/teams/{tid}/members/{outsider['id']}", {"status": 1})
    owner_client.call("PUT", f"/teams/{tid}/owner", {"userId": member["id"]})
    check("Team ownership transferred", member_client.call("GET", f"/teams/{tid}")["ownerUserId"] == member["id"])
    owner_client.call("PATCH", f"/teams/{tid}", {"description": "old owner"}, expected=403)
    member_client.call("DELETE", f"/teams/{tid}")
    owner_client.call("GET", f"/teams/{tid}", expected=403)
    admin.call("PUT", f"/users/{member['id']}/disable", {})
    admin.call("PUT", f"/teams/{tid}/enable", {}, expected=409)
    member_client.call("GET", "/auth/me", expected=401)
    admin.call("PUT", f"/users/{member['id']}/activate", {})
    member_client.login(member["login"], password)
    member_client.call("PUT", f"/teams/{tid}/enable", {})
    member_client.call("PUT", f"/teams/{tid}/owner", {"userId": uid})
    owner_client.call("PATCH", "/auth/me", {"displayName": "持久化验证用户"})
    concurrent_team = owner_client.call("POST", "/teams", {"name": "并发转交验证 " + suffix})
    cid = concurrent_team["id"]
    for candidate in [member, outsider]:
        owner_client.call("PUT", f"/teams/{cid}/members/{candidate['id']}", {})
    other_owner_session = Client(base)
    other_owner_session.login(owner["login"], password)

    def transfer(client, target):
        req = urllib.request.Request(base + f"/api/teams/{cid}/owner", method="PUT",
             data=json.dumps({"userId": target}).encode(),
             headers={"Content-Type": "application/json", "X-CSRF-Token": client.csrf})
        try:
            response = client.http.open(req, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        response.read()
        return response.code

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(transfer, owner_client, member["id"]), pool.submit(transfer, other_owner_session, outsider["id"])]
        statuses = sorted(f.result() for f in futures)
    check("Concurrent ownership transfers serialize with one authorized winner", statuses == [200, 403])
    admin.call("DELETE", f"/teams/{cid}")

    blocked = user("blocked", 0)
    blocked_client = Client(base)
    blocked_client.csrf = blocked_client.call("GET", "/auth/csrf")["csrfToken"]
    blocked_client.call("POST", "/auth/login", {"login": blocked["login"], "password": password}, expected=401)
    admin.call("PUT", f"/users/{blocked['id']}/activate", {})
    blocked_client.login(blocked["login"], password)
    admin.call("PUT", f"/users/{blocked['id']}/lock", {})
    blocked_client.call("GET", "/auth/me", expected=401)
    admin.call("PUT", f"/users/{blocked['id']}/unlock", {})
    blocked_client.login(blocked["login"], password)
    second_session = Client(base)
    second_session.login(blocked["login"], password)
    blocked_client.call("PUT", "/auth/password", {"oldPassword": "wrong", "newPassword": password}, expected=400)
    blocked_client.call("PUT", "/auth/password", {"oldPassword": password, "newPassword": "short"}, expected=400)
    new_password = "Changed-" + secrets.token_hex(10)
    blocked_client.call("PUT", "/auth/password", {"oldPassword": password, "newPassword": new_password})
    second_session.call("GET", "/auth/me", expected=401)
    blocked_client.call("GET", "/auth/me", expected=401)
    blocked_client.login(blocked["login"], new_password)
    blocked_client.call("POST", "/auth/logout", {})
    blocked_client.call("GET", "/auth/me", expected=401)
    admin.call("DELETE", f"/users/{blocked['id']}")
    check("User removal preserves row as disabled", admin.call("GET", f"/users/{blocked['id']}")["status"] == 0)
    check("Search and pagination work", admin.call("GET", "/users?q=" + owner["login"] + "&pageSize=1")["total"] == 1)
    check("Search values are parameterized", admin.call("GET", "/users?q=" + urllib.parse.quote("' OR 1=1 --"))["total"] == 0)
    (ROOT / ".local/user-test-fixture.json").write_text(json.dumps({"userId": uid, "login": owner["login"], "teamId": tid}))
    (ROOT / ".local/ui-test-account.json").write_text(json.dumps({"login": owner["login"], "password": password}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8083")
    parser.add_argument("--persistence-only", action="store_true")
    args = parser.parse_args()
    if urllib.parse.urlparse(args.base).port != 8083:
        raise SystemExit("Acceptance tests are restricted to the dedicated test application on port 8083.")
    started = time.monotonic()
    failure = None
    try:
        run(args.base, args.persistence_only)
    except Exception as error:
        failure = str(error)
    report = {"time": datetime.now(timezone.utc).isoformat(), "base": args.base,
              "mode": "persistence" if args.persistence_only else "acceptance",
              "durationSeconds": round(time.monotonic() - started, 2),
              "passed": failure is None, "assertions": len(RESULTS), "failure": failure, "results": RESULTS}
    out = ROOT / "logs" / ("users-persistence-results.json" if args.persistence_only else "users-test-results.json")
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "results"}, ensure_ascii=False, indent=2))
    if failure:
        raise SystemExit(1)
