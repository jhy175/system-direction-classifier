"""Exercise method persistence against the dedicated MySQL test application on port 8083."""

from pathlib import Path
import runpy
import secrets


ROOT = Path(__file__).resolve().parents[1]
identity = runpy.run_path(str(ROOT / "scripts/test-users.py"))
Client, check = identity["Client"], identity["check"]
BASE = "http://127.0.0.1:8083"
PREFIX = "/methods"


def run():
    config = dict(line.split("=", 1) for line in
                  (ROOT / ".local/user-module.properties").read_text(encoding="utf-8-sig").splitlines() if "=" in line)
    admin = Client(BASE)
    admin.login(config["adminLogin"], config["adminPassword"])
    Client(BASE).call("GET", PREFIX, expected=401)
    builtins = {row["taxonomyCode"]: row for row in admin.call("GET", PREFIX + "?scope=builtin")["items"]}
    check("Three live built-in methods", set(builtins) == {"moe", "wos_ssci", "wos_scie"})
    source = builtins["moe"]
    source_path = f"{PREFIX}/{source['id']}/versions/{source['defaultVersionId']}"
    original = admin.call("GET", source_path)
    baseline = {stage["stageCode"]: stage for stage in original["stages"]}
    check("Six real configured stages", list(baseline) == ["parse", "router", "recall", "judge", "post", "result"])
    taxonomy = source["taxonomyId"]
    dependencies = admin.call("GET", f"{PREFIX}/dependencies?taxonomyId={taxonomy}")
    knowledge = next(row["id"] for row in dependencies["knowledgeVersions"] if row["isDefault"])
    wrong_knowledge = admin.call("GET", f"{PREFIX}/{builtins['wos_ssci']['id']}")["versions"][0]["knowledgeVersionId"]
    label = "方法验收 " + secrets.token_hex(5)
    admin.call("POST", PREFIX, {"name": label, "taxonomyId": taxonomy}, expected=403, csrf=False)
    row = admin.call("POST", PREFIX, {"name": label, "taxonomyId": taxonomy, "description": "原始说明"})
    method = f"{PREFIX}/{row['id']}"
    draft = {"draftId": row["currentDraftId"], "revision": 0}
    created = [method]
    guest = None
    try:
        check("Draft is persistent and private", row["visibility"] == "private" and row["latestVersionId"] is None)
        info = admin.call("GET", f"{method}/versions/{draft['draftId']}")
        check("New draft inherits real source prompts", info["stages"][1]["prompts"] == baseline["router"]["prompts"])
        report = admin.call("POST", method + "/draft/validate", draft)
        check("Incomplete drafts can be validated", not report["valid"] and any(x["field"] == "knowledgeVersionId" for x in report["issues"]))
        admin.call("PUT", method + "/draft", {**draft, "knowledgeVersionId": wrong_knowledge}, expected=400)
        admin.call("POST", method + "/draft/publish", {**draft, "changeNote": "应失败"}, expected=422)
        cap = {"stageCode": "post", "config": {"high_relevance_cap": 2}}
        saved = admin.call("PUT", method + "/draft", {**draft, "knowledgeVersionId": knowledge, "stageChanges": [cap]})["version"]
        check("Saving draft increments revision", saved["revision"] == 1)
        admin.call("PUT", method + "/draft", draft, expected=409)
        check("Saved draft can be reopened", admin.call("GET", f"{method}/versions/{draft['draftId']}")["stages"][4]["config"]["high_relevance_cap"] == 2)
        current = {**draft, "revision": 1}
        bad = {**current, "stageChanges": [{"stageCode": "router", "prompts": {"user": "没有变量"}}]}
        issues = admin.call("POST", method + "/draft/publish", {**bad, "changeNote": "坏提示词"}, expected=422)["issues"]
        check("Publication rejects an invalid prompt without saving", any(x["stage"] == "router" and x["field"] == "user" for x in issues))
        published = admin.call("POST", method + "/draft/publish", {**current, "changeNote": "保存第一版"})
        v1 = published["version"]
        check("First saved version is default and immutable", v1["versionNo"] == 1 and published["method"]["defaultVersionId"] == v1["id"]
              and published["method"]["currentDraftId"] is None)
        check("Publication preserves the exact algorithm fallback order", v1["stages"][2]["config"]["fallback_discipline_order"] ==
              baseline["recall"]["config"]["fallback_discipline_order"])
        admin.call("PUT", method + "/draft", current, expected=409)
        path_v1 = f"{method}/versions/{v1['id']}"
        draft2 = admin.call("POST", method + "/draft", {"versionId": v1["id"]})["currentDraftId"]
        admin.call("POST", method + "/draft", {"versionId": v1["id"]}, expected=409)
        v2 = admin.call("POST", method + "/draft/publish", {"draftId": draft2, "revision": 0, "changeNote": "保存第二版",
            "stageChanges": [{"stageCode": "post", "config": {"high_relevance_cap": 4}}]})
        check("Latest version and default are independent", v2["method"]["latestVersionId"] == v2["version"]["id"] and
              v2["method"]["defaultVersionId"] == v1["id"])
        check("Old version is unchanged", admin.call("GET", path_v1) == v1)
        admin.call("PUT", f"{method}/versions/{v2['version']['id']}/default")
        copy = admin.call("POST", path_v1 + "/copy", {"name": label + " 副本"})
        copy_path = f"{PREFIX}/{copy['id']}"
        created.append(copy_path)
        check("Copy records the selected source revision", copy["copiedFromVersionId"] == v1["id"] and copy["latestVersionId"] is None)
        admin.call("PUT", method, {"name": label + " 重命名", "description": None, "visibility": "private"})
        reopened = admin.call("GET", method)
        check("Saved metadata and default survive a new request", reopened["description"] is None and
              reopened["defaultVersionId"] == v2["version"]["id"])
        options = admin.call("GET", PREFIX + "/options")
        check("Only saved methods are selectable", any(row["id"] == row_id(method) for row in options) and
              all(row["id"] != copy["id"] for row in options))

        role = next(role for role in admin.call("GET", "/roles?pageSize=100")["items"] if role["code"] == "USER")
        login, password = "it_method_" + secrets.token_hex(5), "Test!" + secrets.token_urlsafe(18)
        guest = admin.call("POST", "/users", {"login": login, "password": password, "status": 1})["id"]
        admin.call("PUT", f"/users/{guest}/roles", [role["id"]])
        user = Client(BASE)
        user.login(login, password)
        user.call("GET", method, expected=404)
        user.call("POST", f"{PREFIX}/{source['id']}/draft", {"versionId": original["id"]}, expected=403)
        check("A reader cannot see a private method", all(item["id"] != row_id(method) for item in user.call("GET", PREFIX)["items"]))

        admin.call("PUT", method + "/archive", {"archived": True})
        check("Archive removes the method from available choices", all(row["id"] != row_id(method) for row in admin.call("GET", PREFIX + "/options")))
        admin.call("PUT", method + "/archive", {"archived": False})
        check("Restore keeps the saved history", admin.call("GET", path_v1) == v1)
        check("The built-in version was preserved", admin.call("GET", source_path) == original)
    finally:
        if guest:
            admin.call("PATCH", f"/users/{guest}", {"status": 0})
        for item in created:
            admin.call("PUT", item + "/archive", {"archived": True})


def row_id(path):
    return int(path.rsplit("/", 1)[1])


if __name__ == "__main__":
    run()
    print(f"PASS: {len(identity['RESULTS'])} method API assertions")
