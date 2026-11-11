"""Import the current direction-classifier JSON resources into the local MySQL database."""

import argparse
import ast
import configparser
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import tempfile


PROJECT = Path(__file__).resolve().parents[1]
ASSET_ROOT = PROJECT / ".local" / "knowledge-assets"
DATABASES = ("direction_classifier", "direction_classifier_test")
SYSTEM_NAMES = {"moe": "教育部", "wos_ssci": "WOS SSCI", "wos_scie": "WOS SCIE"}
TASK_FIELDS = ("core_tasks", "peripheral_tasks", "cross_tasks", "differentiation")
IMPORTER_VERSION = "api-core-json-1"
JUDGMENT_VERSION = "api-core-judgment-1"


def json_text(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def sha256(raw):
    return hashlib.sha256(raw).hexdigest()


def json_hash(value):
    return sha256(json_text(value).encode("utf-8"))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def required_text(record, key, location):
    value = record.get(key)
    require(isinstance(value, str) and value.strip(), f"{location}: missing or invalid {key}")
    return value.strip()


@dataclass(frozen=True)
class SourceFile:
    logical_name: str
    role: str
    raw: bytes
    digest: str
    data: object

    @property
    def storage_key(self):
        return f"sha256/{self.digest[:2]}/{self.digest}"

    def manifest_entry(self):
        return {"path": self.logical_name, "role": self.role, "bytes": len(self.raw), "sha256": self.digest}


@dataclass(frozen=True)
class Record:
    code: str
    data: dict
    source: SourceFile
    pointer: str

    @property
    def location(self):
        return {"path": self.source.logical_name, "json_pointer": self.pointer}


@dataclass(frozen=True)
class Bundle:
    system: str
    files: list
    portraits: dict
    summaries: dict
    clusters: dict

    @property
    def manifest(self):
        return {
            "format": IMPORTER_VERSION,
            "system": self.system,
            "files": [source.manifest_entry() for source in self.files],
        }

    @property
    def counts(self):
        return {
            "portraits": len(self.portraits),
            "summaries": len(self.summaries),
            "clusters": len(self.clusters),
            "cluster_members": sum(len(record.data["discipline_ids"]) for record in self.clusters.values()),
            "files": len(self.files),
        }


def system_config(root):
    # Read only the literal resource paths. Importing api_core would initialize model clients.
    module = ast.parse((root / "api_core.py").read_text(encoding="utf-8-sig"))
    for statement in module.body:
        if isinstance(statement, ast.AnnAssign) and getattr(statement.target, "id", None) == "SYSTEMS":
            systems = statement.value
            break
        if isinstance(statement, ast.Assign) and any(
            getattr(target, "id", None) == "SYSTEMS" for target in statement.targets
        ):
            systems = statement.value
            break
    else:
        raise ValueError("api_core.py does not define SYSTEMS")
    require(isinstance(systems, ast.Dict), "SYSTEMS must declare resource paths as a dictionary")
    config = {}
    keys = {"portrait_dir", "source_tag", "summary_file", "cluster_file"}
    for system, values in zip(systems.keys, systems.values):
        name = ast.literal_eval(system)
        require(isinstance(values, ast.Dict), f"{name}: invalid SYSTEMS entry")
        fields = {
            key.value: ast.literal_eval(value)
            for key, value in zip(values.keys, values.values)
            if isinstance(key, ast.Constant) and key.value in keys
        }
        require(fields.keys() == keys and all(isinstance(value, str) and value for value in fields.values()),
                f"{name}: incomplete resource configuration")
        require(name not in config, f"Duplicate system: {name}")
        config[name] = fields
    require(config.keys() == SYSTEM_NAMES.keys(), "SYSTEMS differs from the supported three taxonomies")
    return config


def source_file(root, path, role):
    resolved = path.resolve(strict=True)
    require(resolved.is_relative_to(root) and resolved.is_file() and ".." not in path.parts,
            f"Invalid resource path: {path}")
    logical_name = path.relative_to(root).as_posix()
    raw = resolved.read_bytes()
    try:
        data = json.loads(raw.decode("utf-8-sig"), object_pairs_hook=unique_object)
    except ValueError as error:
        raise ValueError(f"{logical_name}: {error}") from error
    return SourceFile(logical_name, role, raw, sha256(raw), data)


def add_record(records, code, value, source, pointer):
    require(code not in records, f"{source.logical_name}{pointer}: duplicate code {code}")
    records[code] = Record(code, value, source, pointer)


def judgment_code(code):
    # Matches _norm_cid without using its representation for the database identity.
    if re.fullmatch(r"[+-]?\d+\.0+", code):
        code = code.split(".", 1)[0]
    return (code.lstrip("0") or "0") if re.fullmatch(r"\d+", code) else code


def judgment_text(portrait):
    base = portrait["final_definition"].strip()
    expanded = (portrait.get("expanded_portraits") or "").strip()
    return f"{base}\n\n{expanded}" if expanded and expanded not in base else base


def validate_keywords(record, key, location):
    values = record.get(key, [])
    require(isinstance(values, list) and all(isinstance(value, str) for value in values),
            f"{location}: invalid {key}")


def read_bundle(root, system, config):
    portraits, summaries, clusters = {}, {}, {}
    files = []
    directory = root / config["portrait_dir"]
    require(directory.resolve(strict=True).is_relative_to(root), f"Invalid portrait directory: {directory}")
    prefix = config["source_tag"].lower() + "_"
    for path in sorted(directory.glob("*portrait*.json")):
        if not path.name.lower().startswith(prefix):
            continue
        source = source_file(root, path, "portrait_source")
        data = source.data
        where = source.logical_name
        require(isinstance(data, dict), f"{where}: expected portrait object")
        code = required_text(data, "category_id" if "category_id" in data else "discipline_code", where)
        required_text(data, "category_name" if "category_name" in data else "discipline_name", where)
        required_text(data, "final_definition", where)
        require(data.get("expanded_portraits") is None or isinstance(data["expanded_portraits"], str),
                f"{where}: invalid expanded_portraits")
        for field in TASK_FIELDS:
            require(isinstance(data.get(field), list) and all(isinstance(item, dict) for item in data[field]),
                    f"{where}: invalid {field}")
        add_record(portraits, code, data, source, "")
        files.append(source)
    require(portraits, f"{system}: no configured portraits found")
    require(len({judgment_code(code) for code in portraits}) == len(portraits),
            f"{system}: portrait codes collide in the current judgment loader")

    summary_file = source_file(root, root / config["summary_file"], "summary_source")
    files.append(summary_file)
    require(isinstance(summary_file.data, list), f"{summary_file.logical_name}: expected summary array")
    for index, data in enumerate(summary_file.data):
        where = f"{summary_file.logical_name}/{index}"
        require(isinstance(data, dict), f"{where}: expected summary object")
        code = required_text(data, "category_id", where)
        name = required_text(data, "category_name", where)
        required_text(data, "summary", where)
        validate_keywords(data, "summary_keywords", where)
        require(code in portraits and portrait_name(portraits[code]) == name,
                f"{where}: summary code or name does not match a portrait: {code}")
        add_record(summaries, code, data, summary_file, f"/{index}")
    require(summaries.keys() == portraits.keys(), f"{system}: summaries do not cover the portrait set")

    cluster_file = source_file(root, root / config["cluster_file"], "cluster_source")
    files.append(cluster_file)
    require(isinstance(cluster_file.data, dict) and isinstance(cluster_file.data.get("clusters"), list),
            f"{cluster_file.logical_name}: expected clusters array")
    seen = set()
    for index, data in enumerate(cluster_file.data["clusters"]):
        where = f"{cluster_file.logical_name}/clusters/{index}"
        require(isinstance(data, dict), f"{where}: expected cluster object")
        code = required_text(data, "cluster_id", where)
        required_text(data, "cluster_name", where)
        required_text(data, "cluster_summary", where)
        validate_keywords(data, "keywords", where)
        members = data.get("discipline_ids")
        require(isinstance(members, list) and members, f"{where}: no cluster members")
        for member in members:
            require(isinstance(member, str) and member in summaries, f"{where}: unknown discipline {member}")
            require(member not in seen, f"{where}: repeated discipline {member}")
            seen.add(member)
        add_record(clusters, code, data, cluster_file, f"/clusters/{index}")
    require(seen == summaries.keys(), f"{system}: clusters do not cover the summary set")
    return Bundle(system, files, portraits, summaries, clusters)


def portrait_name(record):
    data = record.data
    return (data.get("category_name") or data["discipline_name"]).strip()


def asset_path(source):
    path = ASSET_ROOT / source.storage_key
    require(path.resolve().is_relative_to(ASSET_ROOT) and ASSET_ROOT.resolve().is_relative_to(PROJECT),
            "Asset path resolves outside the project asset directory")
    return path


def persist_asset(source):
    path = asset_path(source)
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
            pending = Path(stream.name)
            try:
                stream.write(source.raw)
                stream.close()
                pending.replace(path)
            finally:
                pending.unlink(missing_ok=True)
    require(path.read_bytes() == source.raw, f"Stored asset is different: {source.logical_name}")


def connect(database):
    try:
        import pymysql
    except ImportError as error:
        raise ValueError("Run with the algorithm project's Python environment containing PyMySQL") from error
    settings = dict(line.split("=", 1) for line in
                    (PROJECT / ".local/user-module.properties").read_text(encoding="utf-8-sig").splitlines()
                    if "=" in line)
    server = configparser.ConfigParser()
    server.read(PROJECT / ".tools/mysql/data/auto.cnf", encoding="utf-8-sig")
    connection = pymysql.connect(
        host="127.0.0.1", port=int(settings["mysqlPort"]), user="classifier_app",
        password=settings["dbPassword"], database=database, charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor, connect_timeout=5,
    )
    try:
        with connection.cursor() as cursor:
            instance = one(cursor, "SELECT @@server_uuid AS uuid, DATABASE() AS db")
            require(instance["db"] == database and instance["uuid"].lower() == server["auto"]["server-uuid"].lower(),
                    "Connection does not match the project-local MySQL instance")
            require(one(cursor, """
                SELECT COUNT(*) AS applied FROM DATABASECHANGELOG
                WHERE ID = 'knowledge-3.0.0-index-delete-guard' AND EXECTYPE = 'EXECUTED'
            """)["applied"] == 1, "Apply the project's MySQL Liquibase migrations first")
        connection.rollback()
        return connection
    except Exception:
        connection.close()
        raise


def one(cursor, sql, params=()):
    cursor.execute(sql, params)
    return cursor.fetchone()


def rows(cursor, sql, params=()):
    cursor.execute(sql, params)
    return cursor.fetchall()


def insert(cursor, table, **columns):
    # Call sites supply fixed table and column identifiers. Values use driver parameters.
    markers = ", ".join(["%s"] * len(columns))
    cursor.execute(f"INSERT INTO {table} ({', '.join(columns)}) VALUES ({markers})", tuple(columns.values()))
    return cursor.lastrowid


def find_base(cursor, bundle):
    return one(cursor, """
        SELECT b.id, b.taxonomy_id, b.current_draft_id, b.latest_version_id,
               b.default_version_id, b.visibility, b.archived_at, t.code AS taxonomy_code
        FROM demo_knowledge_base b JOIN demo_taxonomy t ON t.id = b.taxonomy_id
        WHERE b.builtin_key = %s
    """, (bundle.system,))


def find_version(cursor, base_id, manifest_hash):
    return one(cursor, """
        SELECT id, version_no, manifest_json FROM demo_knowledge_version
        WHERE knowledge_base_id = %s AND manifest_hash = %s AND state = 'saved'
        ORDER BY version_no LIMIT 1
    """, (base_id, manifest_hash))


def ensure_base(cursor, bundle):
    base = find_base(cursor, bundle)
    if base:
        require(base["taxonomy_code"] == bundle.system and base["visibility"] == "builtin"
                and base["archived_at"] is None, f"{bundle.system}: unexpected builtin knowledge base")
        return base
    system_name = SYSTEM_NAMES[bundle.system]
    taxonomy = one(cursor, "SELECT id FROM demo_taxonomy WHERE code = %s", (bundle.system,))
    taxonomy_id = taxonomy["id"] if taxonomy else insert(
        cursor, "demo_taxonomy", code=bundle.system, name=system_name, source_name=system_name,
    )
    owner = one(cursor, """
        SELECT u.id FROM demo_user u JOIN demo_user_role ur ON ur.user_id = u.id
        JOIN demo_role r ON r.id = ur.role_id
        WHERE u.login = 'admin' AND u.status = 1 AND r.code = 'ADMIN' AND r.status = 1
    """)
    insert(cursor, "demo_knowledge_base", taxonomy_id=taxonomy_id, builtin_key=bundle.system,
           name=f"{system_name} 学科知识库", visibility="builtin",
           owner_user_id=owner["id"] if owner else None,
           description="从当前学科方向识别 API 使用的画像、召回摘要和学科簇导入。")
    return find_base(cursor, bundle)


def add_asset_reference(cursor, asset_id, object_type, object_id, role):
    insert(cursor, "demo_asset_reference", asset_id=asset_id, object_type=object_type,
           object_id=object_id, role=role)


def store_files(cursor, version_id, bundle):
    ids = {}
    for order, source in enumerate(bundle.files, 1):
        persist_asset(source)
        existing = one(cursor, """
            SELECT id, byte_size, content_sha256, status FROM demo_file_asset WHERE storage_key = %s
        """, (source.storage_key,))
        if existing:
            require(existing["byte_size"] == len(source.raw) and existing["content_sha256"] == source.digest
                    and existing["status"] == "ready", f"Conflicting asset: {source.logical_name}")
            asset_id = existing["id"]
        else:
            asset_id = insert(cursor, "demo_file_asset", original_name=Path(source.logical_name).name,
                              storage_key=source.storage_key, media_type="application/json",
                              byte_size=len(source.raw), content_sha256=source.digest, status="ready")
        ids[source.logical_name] = asset_id
        insert(cursor, "demo_knowledge_asset", knowledge_version_id=version_id, asset_id=asset_id,
               role=source.role, logical_name=source.logical_name, sort_order=order)
        add_asset_reference(cursor, asset_id, "knowledge_version", version_id, source.role)
    return ids


def store_portraits(cursor, version_id, taxonomy_id, bundle, file_ids):
    ids = {}
    for code, portrait in bundle.portraits.items():
        name = portrait_name(portrait)
        data = portrait.data
        existing = one(cursor, """
            SELECT id FROM demo_discipline WHERE taxonomy_id = %s AND canonical_code = %s
        """, (taxonomy_id, code))
        discipline_id = existing["id"] if existing else insert(
            cursor, "demo_discipline", taxonomy_id=taxonomy_id, canonical_code=code, display_name=name,
        )
        text = judgment_text(data)
        ids[code] = insert(
            cursor, "demo_knowledge_discipline", knowledge_version_id=version_id, taxonomy_id=taxonomy_id,
            discipline_id=discipline_id, code_snapshot=code, name_snapshot=name,
            source_code=data.get("category_id") or data["discipline_code"],
            source_label=data.get("source"), source_tag_raw=data.get("source_tag"),
            source_portrait_version=data.get("version"), portrait_schema_version=f"{bundle.system}-task-portrait-1",
            portrait_json=json_text(data), original_definition=data.get("original_definition"),
            final_definition=data["final_definition"], expanded_portraits=data.get("expanded_portraits"),
            judgment_text=text, judgment_projection_version=JUDGMENT_VERSION, text_sync_status="imported",
            content_hash=json_hash(data), judgment_text_hash=sha256(text.encode("utf-8")),
            source_asset_id=file_ids[portrait.source.logical_name], source_locator_json=json_text(portrait.location),
        )
    return ids


def summary_provenance(summary, portrait):
    cached_definition = summary.data.get("definition")
    return {
        "cached_portrait_file": summary.data.get("portrait_file"),
        "cached_portrait_version": summary.data.get("version"),
        "cached_definition_matches_imported_portrait": (
            isinstance(cached_definition, str)
            and cached_definition.strip() == portrait.data["final_definition"].strip()
        ),
    }


def store_summaries(cursor, version_id, bundle, file_ids, discipline_ids):
    ids = {}
    for code, summary in bundle.summaries.items():
        data = summary.data
        ids[code] = insert(
            cursor, "demo_knowledge_summary", knowledge_version_id=version_id,
            knowledge_discipline_id=discipline_ids[code], summary_text=data["summary"],
            keywords_json=json_text(data.get("summary_keywords", [])), provenance_status="imported_unknown",
            provenance_json=json_text(summary_provenance(summary, bundle.portraits[code])),
            content_hash=json_hash(data), source_asset_id=file_ids[summary.source.logical_name],
            source_locator_json=json_text(summary.location),
        )
    return ids


def store_clusters(cursor, version_id, bundle, file_ids, discipline_ids):
    ids = {}
    for order, (code, cluster) in enumerate(bundle.clusters.items(), 1):
        data = cluster.data
        ids[code] = insert(
            cursor, "demo_knowledge_cluster", knowledge_version_id=version_id, cluster_code=code,
            name=data["cluster_name"], summary_text=data["cluster_summary"],
            keywords_json=json_text(data.get("keywords", [])), sort_order=order,
            provenance_json=json_text({"status": "imported_unknown"}), content_hash=json_hash(data),
            source_asset_id=file_ids[cluster.source.logical_name], source_locator_json=json_text(cluster.location),
        )
        for position, member in enumerate(data["discipline_ids"], 1):
            insert(cursor, "demo_knowledge_cluster_member", knowledge_version_id=version_id,
                   cluster_id=ids[code], knowledge_discipline_id=discipline_ids[member], sort_order=position)
    return ids


def store_import(cursor, version_id, resource_type, records, resource_ids, file_ids):
    asset_ids = {file_ids[record.source.logical_name] for record in records.values()}
    input_asset = next(iter(asset_ids)) if len(asset_ids) == 1 else None
    import_id = insert(
        cursor, "demo_knowledge_import", target_version_id=version_id, target_revision=0,
        source_asset_id=input_asset, resource_type=resource_type, parser_version=IMPORTER_VERSION,
        status="applied", counts_json=json_text({"valid": len(records), "applied": len(records), "invalid": 0}),
    )
    cursor.execute("UPDATE demo_knowledge_import SET applied_at = UTC_TIMESTAMP() WHERE id = %s", (import_id,))
    for asset_id in asset_ids:
        add_asset_reference(cursor, asset_id, "knowledge_import", import_id, "import_source")
    for code, record in records.items():
        insert(
            cursor, "demo_knowledge_import_item", import_id=import_id, source_key=code,
            source_asset_id=file_ids[record.source.logical_name], source_locator_json=json_text(record.location),
            validation_status="valid", action="insert", applied_resource_id=resource_ids[code],
        )


def import_bundle(cursor, bundle):
    base = ensure_base(cursor, bundle)
    base_id, taxonomy_id = base["id"], base["taxonomy_id"]
    manifest_hash = json_hash(bundle.manifest)
    existing = find_version(cursor, base_id, manifest_hash)
    if existing:
        return "already_imported"
    require(base["current_draft_id"] is None, f"{bundle.system}: resolve the existing draft before importing a new version")
    version_no = one(cursor, """
        SELECT COALESCE(MAX(version_no), 0) + 1 AS next_version
        FROM demo_knowledge_version WHERE knowledge_base_id = %s
    """, (base_id,))["next_version"]
    version_id = insert(cursor, "demo_knowledge_version", knowledge_base_id=base_id, taxonomy_id=taxonomy_id,
                        based_on_version_id=base["latest_version_id"], schema_version=IMPORTER_VERSION)
    file_ids = store_files(cursor, version_id, bundle)
    discipline_ids = store_portraits(cursor, version_id, taxonomy_id, bundle, file_ids)
    summary_ids = store_summaries(cursor, version_id, bundle, file_ids, discipline_ids)
    cluster_ids = store_clusters(cursor, version_id, bundle, file_ids, discipline_ids)
    for kind, records, resource_ids in (
        ("portrait", bundle.portraits, discipline_ids),
        ("summary", bundle.summaries, summary_ids),
        ("cluster", bundle.clusters, cluster_ids),
    ):
        store_import(cursor, version_id, kind, records, resource_ids, file_ids)
    cursor.execute("""
        UPDATE demo_knowledge_version
        SET state = 'saved', version_no = %s, saved_at = UTC_TIMESTAMP(), manifest_hash = %s,
            manifest_json = %s, validation_report_json = %s, change_note = %s
        WHERE id = %s
    """, (version_no, manifest_hash, json_text(bundle.manifest),
          json_text({"counts": bundle.counts, "llm_cluster_partition": "valid", "origin": "api_core.SYSTEMS"}),
          "导入当前算法使用的学科画像、召回摘要与学科簇。", version_id))
    cursor.execute("""
        UPDATE demo_knowledge_base SET latest_version_id = %s, default_version_id = COALESCE(default_version_id, %s)
        WHERE id = %s
    """, (version_id, version_id, base_id))
    return "imported"


def verify_files(cursor, bundle, version_id):
    actual = rows(cursor, """
        SELECT k.logical_name, k.role, a.id, a.storage_key, a.content_sha256, a.byte_size, a.status
        FROM demo_knowledge_asset k JOIN demo_file_asset a ON a.id = k.asset_id
        WHERE k.knowledge_version_id = %s ORDER BY k.sort_order
    """, (version_id,))
    require(len(actual) == len(bundle.files), f"{bundle.system}: incorrect file count")
    result = {}
    for row, source in zip(actual, bundle.files):
        require((row["logical_name"], row["role"], row["storage_key"], row["content_sha256"],
                 row["byte_size"], row["status"]) ==
                (source.logical_name, source.role, source.storage_key, source.digest, len(source.raw), "ready")
                and asset_path(source).read_bytes() == source.raw,
                f"{bundle.system}: asset verification failed for {source.logical_name}")
        result[source.logical_name] = row["id"]
    return result


def verify_portraits(cursor, bundle, version_id, file_ids):
    actual = rows(cursor, """
        SELECT p.*, d.canonical_code FROM demo_knowledge_discipline p
        JOIN demo_discipline d ON d.id = p.discipline_id
        WHERE p.knowledge_version_id = %s
    """, (version_id,))
    require({row["code_snapshot"] for row in actual} == bundle.portraits.keys(),
            f"{bundle.system}: incorrect portrait coverage")
    for row in actual:
        code = row["code_snapshot"]
        record = bundle.portraits[code]
        data = record.data
        text = judgment_text(data)
        require(row["canonical_code"] == code and row["name_snapshot"] == portrait_name(record)
                and json.loads(row["portrait_json"]) == data and row["content_hash"] == json_hash(data)
                and row["original_definition"] == data.get("original_definition")
                and row["final_definition"] == data["final_definition"]
                and row["expanded_portraits"] == data.get("expanded_portraits")
                and row["judgment_text"] == text and row["judgment_text_hash"] == sha256(text.encode("utf-8"))
                and row["source_asset_id"] == file_ids[record.source.logical_name]
                and json.loads(row["source_locator_json"]) == record.location,
                f"{bundle.system}: portrait verification failed for {code}")


def verify_summaries(cursor, bundle, version_id, file_ids):
    actual = rows(cursor, """
        SELECT s.*, p.code_snapshot FROM demo_knowledge_summary s
        JOIN demo_knowledge_discipline p ON p.id = s.knowledge_discipline_id
        WHERE s.knowledge_version_id = %s
    """, (version_id,))
    require({row["code_snapshot"] for row in actual} == bundle.summaries.keys(),
            f"{bundle.system}: incorrect summary coverage")
    for row in actual:
        code = row["code_snapshot"]
        record = bundle.summaries[code]
        data = record.data
        require(row["summary_text"] == data["summary"] and json.loads(row["keywords_json"]) == data.get("summary_keywords", [])
                and row["content_hash"] == json_hash(data) and row["source_portrait_hash"] is None
                and row["source_input_hash"] is None and row["provenance_status"] == "imported_unknown"
                and json.loads(row["provenance_json"]) == summary_provenance(record, bundle.portraits[code])
                and row["source_asset_id"] == file_ids[record.source.logical_name]
                and json.loads(row["source_locator_json"]) == record.location,
                f"{bundle.system}: summary verification failed for {code}")


def verify_clusters(cursor, bundle, version_id, file_ids):
    actual = rows(cursor, """
        SELECT * FROM demo_knowledge_cluster WHERE knowledge_version_id = %s ORDER BY sort_order
    """, (version_id,))
    require([row["cluster_code"] for row in actual] == list(bundle.clusters),
            f"{bundle.system}: incorrect cluster coverage or order")
    members = {code: [] for code in bundle.clusters}
    for row in rows(cursor, """
        SELECT c.cluster_code, p.code_snapshot FROM demo_knowledge_cluster_member m
        JOIN demo_knowledge_cluster c ON c.id = m.cluster_id
        JOIN demo_knowledge_discipline p ON p.id = m.knowledge_discipline_id
        WHERE m.knowledge_version_id = %s ORDER BY c.sort_order, m.sort_order
    """, (version_id,)):
        members[row["cluster_code"]].append(row["code_snapshot"])
    for row in actual:
        code = row["cluster_code"]
        record = bundle.clusters[code]
        data = record.data
        require(row["name"] == data["cluster_name"] and row["summary_text"] == data["cluster_summary"]
                and json.loads(row["keywords_json"]) == data.get("keywords", [])
                and members[code] == data["discipline_ids"] and row["content_hash"] == json_hash(data)
                and row["source_asset_id"] == file_ids[record.source.logical_name]
                and json.loads(row["source_locator_json"]) == record.location,
                f"{bundle.system}: cluster verification failed for {code}")


def verify_bundle(cursor, bundle):
    base = find_base(cursor, bundle)
    require(base is not None and base["taxonomy_code"] == bundle.system and base["visibility"] == "builtin",
            f"{bundle.system}: builtin knowledge base missing")
    manifest_hash = json_hash(bundle.manifest)
    version = find_version(cursor, base["id"], manifest_hash)
    require(version is not None and json.loads(version["manifest_json"]) == bundle.manifest,
            f"{bundle.system}: saved version for source manifest is missing")
    version_id = version["id"]
    file_ids = verify_files(cursor, bundle, version_id)
    verify_portraits(cursor, bundle, version_id, file_ids)
    verify_summaries(cursor, bundle, version_id, file_ids)
    verify_clusters(cursor, bundle, version_id, file_ids)
    import_counts = rows(cursor, """
        SELECT i.resource_type, COUNT(m.id) AS items
        FROM demo_knowledge_import i JOIN demo_knowledge_import_item m ON m.import_id = i.id
        WHERE i.target_version_id = %s AND i.status = 'applied' AND m.validation_status = 'valid'
            AND m.applied_resource_id IS NOT NULL
        GROUP BY i.resource_type
    """, (version_id,))
    require({row["resource_type"]: row["items"] for row in import_counts} == {
        "portrait": len(bundle.portraits), "summary": len(bundle.summaries), "cluster": len(bundle.clusters),
    }, f"{bundle.system}: incomplete import history")
    return {
        "system": bundle.system, "knowledge_base_id": base["id"], "version_id": version_id,
        "version_no": version["version_no"], "is_latest": base["latest_version_id"] == version_id,
        "is_default": base["default_version_id"] == version_id, "manifest_sha256": manifest_hash, **bundle.counts,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--algorithm-dir", required=True, type=Path, help="directory containing api_core.py")
    parser.add_argument("--database", choices=DATABASES, default=DATABASES[0])
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true", help="import and verify saved knowledge versions")
    mode.add_argument("--verify", action="store_true", help="check existing versions against the current files")
    args = parser.parse_args()
    root = args.algorithm_dir.resolve(strict=True)
    bundles = [read_bundle(root, system, config) for system, config in system_config(root).items()]
    if not (args.apply or args.verify):
        print(json_text({"mode": "preview", "systems": [
            {"system": bundle.system, "manifest_sha256": json_hash(bundle.manifest), **bundle.counts}
            for bundle in bundles
        ]}))
        return
    with connect(args.database) as connection, connection.cursor() as cursor:
        if args.apply:
            require(one(cursor, "SELECT GET_LOCK(%s, 15) AS acquired",
                        (f"{args.database}:knowledge-import",))["acquired"] == 1,
                    "Another knowledge import is running")
            connection.begin()
            actions = [import_bundle(cursor, bundle) for bundle in bundles]
        else:
            actions = ["verified"] * len(bundles)
        report = [{"action": action, **verify_bundle(cursor, bundle)}
                  for action, bundle in zip(actions, bundles)]
        if args.apply:
            connection.commit()
    print(json_text({"database": args.database, "systems": report}))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        raise SystemExit(str(error)) from error
