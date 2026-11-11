"""Exercise the knowledge workflow through the dedicated MySQL test application."""

import base64
import copy
import json
from pathlib import Path
import runpy
import secrets
import urllib.parse


ROOT = Path(__file__).resolve().parents[1]
identity = runpy.run_path(str(ROOT / "scripts/test-users.py"))
Client, check = identity["Client"], identity["check"]
BASE = "http://127.0.0.1:8083"
PREFIX = "/knowledge"


def credentials():
    return dict(line.split("=", 1) for line in
                (ROOT / ".local/user-module.properties").read_text(encoding="utf-8-sig").splitlines() if "=" in line)


def run():
    settings = credentials()
    admin = Client(BASE)
    user = admin.login(settings["adminLogin"], settings["adminPassword"])
    Client(BASE).call("GET", PREFIX, expected=401)
    catalog = admin.call("GET", PREFIX + "?scope=builtin")
    initial = {row["taxonomyCode"]: row for row in catalog["items"]}
    check("Exact initial resource counts", {code: row["profileCount"] for code, row in initial.items()} ==
          {"moe": 95, "wos_ssci": 58, "wos_scie": 178})
    check("Built-in knowledge bases are owned by admin", all(row["ownerLogin"] == "admin" for row in initial.values()))
    source = initial["moe"]
    source_id = source["defaultVersionId"]
    source_url = f"{PREFIX}/{source['id']}/versions/{source_id}"
    saved = admin.call("GET", source_url)
    first = saved["resources"]["profiles"][0]
    original = admin.call("GET", f"{source_url}/portraits/{first['id']}")
    check("Read full portrait and original algorithm text", len(original["portrait"]["core_tasks"]) > 0 and
          bool(original["judgmentText"]) and original["code"].startswith("0"))
    check("Current algorithm resource validation", saved["validation"]["compatible"] is True)

    suffix = secrets.token_hex(4)
    copy_name = "知识库验收副本 " + suffix
    duplicate = admin.call("POST", source_url + "/copy", {"name": copy_name})
    private = f"{PREFIX}/{duplicate['id']}"
    draft_id = duplicate["currentDraftId"]
    draft_url = f"{private}/draft"
    draft_view = f"{private}/versions/{draft_id}"
    draft = admin.call("GET", draft_view)
    check("Copied draft preserves all resource rows", draft["validation"]["profiles"] == 95 and
          draft["validation"]["summaries"] == 95 and draft["validation"]["clusters"] == 18)
    copied = draft["resources"]["profiles"][0]
    copied_portrait = admin.call("GET", f"{draft_view}/portraits/{copied['id']}")
    check("Copied portrait content is exact", copied_portrait["portrait"] == original["portrait"])
    changed = copy.deepcopy(copied_portrait["portrait"])
    changed["original_definition"] += "\n验收修改"
    base_revision = {"draftId": draft_id, "revision": draft["version"]["revision"]}
    result = admin.call("PUT", draft_url + "/resources/profiles", {**base_revision, "item": {
        "id": copied["id"], "code": copied["code"], "name": copied["name"], "portrait": changed}})
    admin.call("PUT", draft_url + "/resources/profiles", {**base_revision, "item": {}}, expected=409)
    draft = admin.call("GET", draft_view)
    check("Stale summary and review warning are persisted", draft["validation"]["stale"] == 1 and
          draft["resources"]["profiles"][0]["textSyncStatus"] == "needs_review")
    check("Original version remains unchanged", admin.call("GET", f"{source_url}/portraits/{first['id']}") == original)
    admin.call("POST", draft_url + "/publish",
               {"draftId": draft_id, "revision": result["revision"], "changeNote": "验收"}, expected=409)
    result = admin.call("PUT", draft_url + "/resources/papers", {"draftId": draft_id, "revision": result["revision"],
        "item": {"title": "知识库验收种子论文", "abstract": "摘要", "authors": ["测试作者"],
                 "sourceRecordId": "CNKI-1", "doiRaw": "CNKI-1",
                 "disciplines": [{"code": copied["code"], "useForGeneration": True}]}})
    paper_id = result["id"]
    raw = b"a,b,similarity\n1,2,0.65\n"
    result = admin.call("POST", draft_url + "/assets", {"draftId": draft_id, "revision": result["revision"],
        "fileName": "kb-test-" + suffix + ".csv", "description": "验收附件",
        "content": base64.b64encode(raw).decode()})
    draft = admin.call("GET", draft_view)
    paper = draft["resources"]["papers"][0]
    check("Paper relationship and original identifier preserved", paper["id"] == paper_id and
          paper["doiRaw"] == "CNKI-1" and paper["doiNormalized"] is None and paper["disciplines"][0]["useForGeneration"])
    asset = draft["assets"][0]
    with admin.http.open(BASE + f"/api{draft_view}/assets/{asset['id']}") as file:
        check("Uploaded file downloads byte-for-byte", file.read() == raw)
    published = admin.call("POST", draft_url + "/publish", {"draftId": draft_id, "revision": result["revision"],
        "changeNote": "验收保存", "allowIncomplete": True})
    check("Saved version has expected ID and number", published == {"versionId": draft_id, "versionNo": 1})
    admin.call("PUT", draft_url + "/resources/profiles", {**base_revision, "item": {}}, expected=409)
    new_draft = admin.call("POST", draft_url, {"versionId": draft_id})["currentDraftId"]
    next_snapshot = admin.call("GET", f"{private}/versions/{new_draft}")
    check("Saved attachment and paper relationships copied into new draft", len(next_snapshot["assets"]) == 1 and
          len(next_snapshot["resources"]["papers"]) == 1)
    result = admin.call("DELETE", f"{draft_url}/resources/papers/{next_snapshot['resources']['papers'][0]['id']}",
        {"draftId": new_draft, "revision": 0})
    check("Removed paper only from new draft", len(admin.call("GET", f"{private}/versions/{new_draft}")["resources"]["papers"]) == 0 and
          len(admin.call("GET", draft_view)["resources"]["papers"]) == 1)

    roles = admin.call("GET", "/roles?pageSize=100")["items"]
    role = next(row for row in roles if row["code"] == "USER")
    username, password = "kb_it_" + suffix, "Kb-Test-" + secrets.token_hex(12)
    guest = admin.call("POST", "/users", {"login": username, "password": password, "status": 1})
    try:
        admin.call("PUT", f"/users/{guest['id']}/roles", [role["id"]])
        member = Client(BASE)
        member.login(username, password)
        member.call("GET", source_url)
        member.call("GET", private, expected=404)
        member.call("POST", f"{PREFIX}/{source['id']}/draft", {"versionId": source_id}, expected=403)
        check("Ordinary account can read only visible saved knowledge", len(member.call("GET", PREFIX)["items"]) >= 3)
    finally:
        admin.call("PATCH", f"/users/{guest['id']}", {"status": 0})

    blank = admin.call("POST", PREFIX, {"taxonomy": "moe", "name": "知识库导入验收 " + suffix})
    target = f"{PREFIX}/{blank['id']}"
    imported_draft = blank["currentDraftId"]
    draft_payload = {"draftId": imported_draft, "revision": 0}
    export = admin.call("GET", source_url + "/export")
    check("Export returns full resource data", len(export["resources"]["profiles"]) == 95 and
          export["resources"]["profiles"][0]["portrait"] == original["portrait"])
    export["resources"] = {"profiles": export["resources"]["profiles"][:1]}
    upload = {**draft_payload, "fileName": "portrait.json", "content": base64.b64encode(
        json.dumps(export, ensure_ascii=False).encode("utf-8")).decode()}
    preview = admin.call("POST", target + "/draft/import", upload)
    check("Import preview validates the selected file", preview["valid"] is True and preview["counts"]["profiles"] == 1)
    admin.call("POST", target + "/draft/import", {**upload,"apply": True,"previewHash": "wrong"}, expected=409)
    applied = admin.call("POST", target + "/draft/import", {**upload,"apply": True,"previewHash": preview["sha256"]})
    duplicate_preview = admin.call("POST", target + "/draft/import", {**upload,"revision": applied["revision"]})
    check("Duplicate import is rejected at preview", duplicate_preview["valid"] is False)
    generation_input = {**draft_payload, "revision": applied["revision"]}
    generation_url = target + "/draft/generate/summaries"
    preview = admin.call("POST", generation_url, generation_input)
    check("Summary generation previews current portrait", len(preview["items"]) == 1 and
          preview["items"][0]["code"] == original["code"])
    generated = admin.call("POST", generation_url, {**generation_input, "apply": True})
    admin.call("POST", generation_url, {**generation_input, "apply": True}, expected=409)
    groups = target + "/draft/generate/clusters"
    assert len(admin.call("POST", groups, {**draft_payload, "revision": generated["revision"]})["items"]) == 1
    generated = admin.call("POST", groups, {**draft_payload, "revision": generated["revision"], "apply": True})
    v1 = admin.call("POST", target + "/draft/publish", {**draft_payload, "revision": generated["revision"], "changeNote": "首次验收"})
    v2 = admin.call("POST", target + "/draft", {"versionId": v1["versionId"]})["currentDraftId"]
    admin.call("POST", target + "/draft/publish", {"draftId": v2, "revision": 0, "changeNote": "第二版验收"})
    check("Publishing preserves an explicitly selected default", admin.call("GET", target)["defaultVersionId"] == v1["versionId"])
    admin.call("PUT", f"{target}/versions/{v2}/default")
    check("Default version can be changed", admin.call("GET", target)["defaultVersionId"] == v2)
    admin.call("PUT", target, {"name": "验收归档 " + suffix, "description": "", "visibility": "private"})
    admin.call("PUT", target + "/archive", {"archived": True})
    check("Archived knowledge is filtered out of the active list",
          not admin.call("GET", PREFIX + "?search=" + urllib.parse.quote("验收归档 " + suffix))["items"])
    check("Archived knowledge appears for owner", len(admin.call("GET", PREFIX + "?archived=true&search=" + suffix)["items"]) == 1)
    admin.call("PUT", private + "/archive", {"archived": True})
    check("Authenticated tests used the requested admin identity", user["login"] == "admin")


if __name__ == "__main__":
    failure = None
    try:
        run()
    except Exception as error:
        failure = str(error) or type(error).__name__
    report = {"passed": failure is None, "failure": failure, "assertions": len(identity["RESULTS"]), "results": identity["RESULTS"]}
    (ROOT / "logs/knowledge-api-test.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "results"}, ensure_ascii=False))
    if failure is not None:
        raise SystemExit(1)
