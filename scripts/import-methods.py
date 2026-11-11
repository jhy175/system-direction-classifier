"""Import the current hierarchical LLM recognition method for all three taxonomies."""

import argparse
import ast
import importlib.util
import json
from pathlib import Path
from string import Formatter
import sys


# Reuse the knowledge importer's source manifest and local MySQL instance checks.
spec = importlib.util.spec_from_file_location("knowledge_import", Path(__file__).with_name("import-knowledge.py"))
knowledge = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = knowledge
spec.loader.exec_module(knowledge)
from knowledge_import import insert, json_hash, json_text, one, require, rows


IMPLEMENTATION = "api-core-hierarchical-llm"
SCHEMA_VERSION = "hierarchical-llm-1"
SOURCE_FILES = (
    "api_service.py", "api_core.py", "run_llm_hierarchical_recall_dynamic.py", "recall_llm_dynamic_new.py",
    "prompts.py", "classifier.py", "llm_client.py", "config.py",
)
METHOD_NAMES = {"moe": "教育部", "wos_ssci": "WoS SSCI", "wos_scie": "WoS SCIE"}
CALL_FIELDS = (
    "rendered_request", "raw_response", "parsed_response", "duration_ms", "model_revision_id", "endpoint",
    "cache_hit", "attempts", "error",
)
OUTPUT_FIELDS = {
    "parse": ("parsed_fields", "field_sources", "missing_fields", "cleaning_and_truncation",
              "recall_text", "judgment_fields"),
    "router": ("available_clusters", "selected_clusters", "route_rank", "route_level", "route_reason",
               "member_codes", "supplemented"),
    "recall": ("candidate_pool", "selected_candidates", "source_clusters", "recall_rank", "recall_level",
               "recall_reason", "raw_score", "effective_score", "score_source", "supplement_source"),
    "judge": ("candidate_code", "judgment_portrait", "raw_level", "confidence", "reason", "matched_module",
              "matched_fields", "matched_features", "boundary_explanation", "paper_core_problem",
              "paper_main_methods", "paper_main_contribution", "judgment_failed"),
    "post": ("candidate_code", "raw_level", "final_level", "rank_before", "rank_after", "sort_score",
             "included", "rule_code", "reason", "judgment_failed"),
    "result": ("complete_ranking", "disciplines", "main_discipline", "result_status"),
}


def assigned(statements, name):
    for statement in statements:
        if isinstance(statement, ast.Assign) and any(isinstance(target, ast.Name) and target.id == name
                                                      for target in statement.targets):
            return statement.value
        if isinstance(statement, ast.AnnAssign) and isinstance(statement.target, ast.Name) and statement.target.id == name:
            return statement.value
    raise ValueError(f"Algorithm source does not assign {name}")


def function(statements, name):
    matches = [node for node in statements if isinstance(node, ast.FunctionDef) and node.name == name]
    require(len(matches) == 1, f"Algorithm source does not define {name}")
    return matches[0]


def call_in(node, name):
    matches = [item for item in ast.walk(node) if isinstance(item, ast.Call) and ast.unparse(item.func) == name]
    require(len(matches) == 1, f"Expected one {name} call in {node.name}")
    return matches[0]


def keywords(call):
    return {item.arg: item.value for item in call.keywords}


def configured_number(statements, name):
    # API constants use typed getenv calls. Capture the source defaults as the method's own values.
    expr = assigned(statements, name)
    require(isinstance(expr, ast.Call) and ast.unparse(expr.func) in ("int", "float")
            and isinstance(expr.args[0], ast.Call) and ast.unparse(expr.args[0].func) == "os.getenv",
            f"Unexpected configuration for {name}")
    default = ast.literal_eval(expr.args[0].args[1])
    return int(default) if expr.func.id == "int" else float(default)


def source_template(node, variables):
    require(isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr == "strip"
            and isinstance(node.func.value, ast.JoinedStr), "Expected a stripped f-string prompt in the algorithm")
    parts = []
    for item in node.func.value.values:
        if isinstance(item, ast.Constant):
            parts.append(item.value.replace("{", "{{").replace("}", "}}"))
            continue
        expression = ast.unparse(item.value)
        require(expression in variables and item.conversion == -1 and item.format_spec is None,
                "The algorithm prompt uses a new expression; update the method importer")
        parts.append("{" + variables[expression] + "}")
    return "".join(parts).strip()


def prompt_config(system, user):
    variables = list(dict.fromkeys(name for _, name, _, _ in Formatter().parse(user) if name is not None))
    return {"syntax": "python_format", "system": system, "user": user, "variables": variables}


def stage(code, strategy, config, prompts=None, call=None):
    contract = {"fields": OUTPUT_FIELDS[code]}
    if call is not None:
        contract["call_fields"] = CALL_FIELDS
    return {
        "stage_code": code, "strategy_code": strategy, "config_json": config,
        "prompt_templates_json": prompts, "call_options_json": call,
        "output_schema_version": f"{code}-1", "output_contract_json": contract,
    }


def read_method(root):
    source = {name: (root / name).read_bytes() for name in SOURCE_FILES}
    source_hashes = {name: knowledge.sha256(content) for name, content in source.items()}
    modules = {name: ast.parse(content, filename=name).body for name, content in source.items()}
    core = modules["api_core.py"]
    recall = modules["run_llm_hierarchical_recall_dynamic.py"]
    judge = modules["recall_llm_dynamic_new.py"]
    classify = function(core, "classify_payload")
    recall_call = keywords(call_in(classify, "_select_disciplines"))
    require(ast.literal_eval(recall_call["dynamic_recall"]) is True, "The online recall strategy is no longer dynamic")

    provider_call = keywords(call_in(function(modules["llm_client.py"], "chat_json"),
                                     "active_client.chat.completions.create"))
    system = ast.literal_eval(provider_call["messages"].elts[0])["content"]
    temperature = ast.literal_eval(assigned(modules["config.py"], provider_call["temperature"].id))
    call_options = {
        "temperature": temperature,
        "max_tokens": ast.literal_eval(provider_call["max_tokens"]),
        "enable_thinking": ast.literal_eval(provider_call["extra_body"])["chat_template_kwargs"]["enable_thinking"],
        "timeout_seconds": max(60, configured_number(core, "LLM_TIMEOUT") or 240),
        "llm_attempt_limit": max(1, configured_number(core, "LLM_MAX_RETRIES") or 3),
        "llm_backoff_seconds_per_attempt": 2,
    }

    router_body = function(recall, "_select_clusters").body
    router_prompt = source_template(assigned(router_body, "prompt"), {
        "paper_text": "paper_text", "max_clusters": "max_clusters",
        "json.dumps(cluster_brief, ensure_ascii=False)": "clusters_json",
    })
    recall_body = function(recall, "_select_disciplines").body
    dynamic = next(node for node in recall_body if isinstance(node, ast.If) and ast.unparse(node.test) == "dynamic_recall")
    recall_instruction = source_template(assigned(dynamic.body, "recall_instruction"), {"top_k": "max_candidates"})
    recall_prompt = source_template(assigned(recall_body, "prompt"), {
        "recall_instruction": "recall_instruction", "paper_text": "paper_text",
        "json.dumps([{k: c.get(k) for k in ['cluster_id', 'cluster_name', 'route_level', 'route_reason']} for c in selected_clusters], ensure_ascii=False)": "selected_clusters_json",
        "json.dumps(candidates, ensure_ascii=False)": "candidate_pool_json",
    }).replace("{recall_instruction}", recall_instruction)
    prompt_version = ast.literal_eval(assigned(core, "PROMPT_VERSION"))
    judge_prompts = assigned(judge, "JUDGE_PROMPTS")
    prompt_names = {ast.literal_eval(key): value.id for key, value in zip(judge_prompts.keys, judge_prompts.values)}
    judge_prompt = ast.literal_eval(assigned(modules["prompts.py"], prompt_names[prompt_version]))
    high_cap = ast.literal_eval(keywords(call_in(function(core, "_restore_original_text_after_cap"),
                                                "_cap_high_relevance"))["max_high"])
    top_k_expr = function(modules["api_service.py"], "_top_k").body[0].value
    top_k_default = ast.literal_eval(top_k_expr.body)
    settings = {name: ast.literal_eval(assigned(core, name)) for name in (
        "MIN_CLUSTERS", "RECALL_TOP_K", "MIN_RECALL_K", "DEFAULT_MAX_CLUSTERS", "DEFAULT_MAX_ABSTRACT_CHARS",
        "IRRELEVANT_LEVEL", "TOP_K_MIN", "TOP_K_MAX",
    )}

    stages = [
        stage("parse", "api_structured_or_cgmc", {
            "input_priority": ["structured_text_fields", "data_cgmc"],
            "cgmc_markers": {"abstract": ["摘要", "Abstract"], "keywords": ["关键词", "Keywords"]},
            "unmarked_cgmc": "title", "require_any": ["title", "keywords", "abstract"],
            "recall_fields": ["title", "keywords", "journal_name", "abstract"],
            "recall_abstract_cleaning": "collapse_whitespace_then_truncate",
            "recall_max_abstract_chars": settings["DEFAULT_MAX_ABSTRACT_CHARS"],
            "judge_fields": ["title", "doi", "journal_name", "pub_year", "abstract"],
            "judge_max_abstract_chars": None,
        }),
        stage("router", "llm_cluster_routing", {
            "min_clusters": settings["MIN_CLUSTERS"], "max_clusters": settings["DEFAULT_MAX_CLUSTERS"],
            "supplement_order": "knowledge_cluster_order", "default_level": "低度相关",
        }, prompt_config(system, router_prompt), {**call_options, "cache_enabled": True}),
        stage("recall", "llm_dynamic_recall", {
            "min_candidates": settings["MIN_RECALL_K"], "max_candidates": settings["RECALL_TOP_K"],
            "supplement_order": ["selected_cluster_member_order", "source_summary_order"],
            "score_clip": [0, 1], "level_score_fallback": ast.literal_eval(assigned(recall, "LEVEL_SCORE")),
            "unknown_level_score_fallback": 0.2, "whole_library_supplement_score": 0.05,
        }, prompt_config(system, recall_prompt), {**call_options, "cache_enabled": True}),
        stage("judge", "llm_independent_candidate", {
            "prompt_source_version": prompt_version, "candidates_per_call": 1,
            "portrait_projection": "api_final_definition_with_expansion", "confidence_type": "llm_label",
        }, prompt_config(system, judge_prompt), {
            **call_options, "cache_enabled": False,
            "candidate_attempt_limit": max(1, configured_number(core, "CANDIDATE_RETRIES") or 3),
            "candidate_backoff_seconds_per_attempt": max(0, configured_number(core, "RETRY_SLEEP")),
        }),
        stage("post", "api_relevance_cap_and_rank", {
            "high_relevance_cap": high_cap, "demote_to": "中度相关",
            "level_scores": ast.literal_eval(assigned(judge, "RELEVANCE_LEVEL_SCORE")),
            "sort": ["level_score_desc", "rounded_recall_score_desc", "recall_rank_asc"],
            "recall_score_multiplier": 1000, "recall_score_rounding": "python_round",
            "excluded_levels": [settings["IRRELEVANT_LEVEL"]], "candidate_failure": "fail_item",
        }),
        stage("result", "api_ranked_disciplines", {
            "top_k_default": top_k_default, "top_k_min": settings["TOP_K_MIN"], "top_k_max": settings["TOP_K_MAX"],
            "main_discipline": "first_included", "allow_empty_success": True, "retain_all_candidates": True,
            "with_reason_affects_storage": False, "optional_metrics": ["external_interdisciplinarity"],
        }),
    ]
    return source_hashes, stages


def stages_for_system(stages, bundle):
    # The last fallback iterates the source summary array's insertion order.
    return [dict(item, config_json={**item["config_json"], "fallback_discipline_order": list(bundle.summaries)})
            if item["stage_code"] == "recall" else item for item in stages]


def admin_id(cursor):
    admin = one(cursor, """
        SELECT u.id FROM demo_user u JOIN demo_user_role ur ON ur.user_id = u.id
        JOIN demo_role r ON r.id = ur.role_id
        WHERE u.login = 'admin' AND u.status = 1 AND r.code = 'ADMIN' AND r.status = 1
    """)
    require(admin is not None, "An enabled admin with the ADMIN role is required")
    return admin["id"]


def model_revision(cursor, connection_id, owner):
    model = one(cursor, """
        SELECT c.id AS connection_id, r.id AS revision_id, r.model_id, c.status,
               (r.auth_type = 'none' OR k.id IS NOT NULL) AS ready,
               (SELECT t.call_status FROM demo_model_connection_test t
                WHERE t.connection_revision_id = r.id AND t.credential_id <=> k.id
                    AND t.tested_base_url = r.base_url ORDER BY t.tested_at DESC, t.id DESC LIMIT 1) AS last_test
        FROM demo_model_connection c
        JOIN demo_model_connection_revision r ON r.connection_id = c.id AND r.id = c.current_revision_id
        LEFT JOIN demo_model_credential k ON k.connection_id = c.id AND k.credential_scope_key = r.credential_scope_key
            AND k.active_slot = 1 AND r.auth_type <> 'none'
        WHERE c.id = %s AND c.owner_user_id = %s AND r.model_type = 'chat'
    """, (connection_id, owner))
    require(model and model["status"] == "enabled" and model["ready"] and model["last_test"] != "failed",
            f"Model connection {connection_id} must be a usable chat model owned by admin")
    return model


def method_snapshot(cursor, bundle, stages, source_hashes, model):
    resources = knowledge.verify_bundle(cursor, bundle)
    snapshot = {
        "implementation_key": IMPLEMENTATION, "implementation_revision": f"sha256:{json_hash(source_hashes)}",
        "config_schema_version": SCHEMA_VERSION, "knowledge_version_id": resources["version_id"],
        "knowledge_manifest_sha256": resources["manifest_sha256"],
        "stages": [dict(item, model_revision_id=model["revision_id"] if item["call_options_json"] else None)
                   for item in stages_for_system(stages, bundle)],
    }
    report = {"configuration": "validated", "source_files_sha256": source_hashes,
              "knowledge_manifest_sha256": resources["manifest_sha256"],
              "resource_counts": bundle.counts, "model_connection_id": model["connection_id"],
              "model_revision_id": model["revision_id"]}
    return snapshot, report


def store_method(cursor, system, taxonomy_id, owner, snapshot, report, apply):
    base = one(cursor, "SELECT * FROM demo_method WHERE builtin_key = %s", (system,))
    if base is None and apply:
        method_id = insert(cursor, "demo_method", taxonomy_id=taxonomy_id, builtin_key=system,
                           name=f"{METHOD_NAMES[system]}学科方向识别",
                           description="LLM 簇路由、动态学科召回和逐候选独立精判。",
                           owner_user_id=owner, visibility="builtin")
        base = one(cursor, "SELECT * FROM demo_method WHERE id = %s", (method_id,))
    require(base and base["taxonomy_id"] == taxonomy_id and base["visibility"] == "builtin"
            and base["owner_user_id"] == owner and base["archived_at"] is None,
            f"{system}: expected an active builtin method owned by admin")
    method_id = base["id"]
    if apply:
        base = one(cursor, "SELECT * FROM demo_method WHERE id = %s FOR UPDATE", (method_id,))

    config_hash = json_hash(snapshot)
    version = one(cursor, """
        SELECT * FROM demo_method_version WHERE method_id = %s AND state = 'saved' AND config_hash = %s
        ORDER BY version_no LIMIT 1
    """, (method_id, config_hash))
    action = "reused" if apply else "verified"
    if version is None and apply:
        require(base["current_draft_id"] is None, f"{system}: an existing method draft must be resolved first")
        revision_fields = {key: snapshot[key] for key in ("knowledge_version_id", "implementation_key",
                                                            "implementation_revision", "config_schema_version")}
        version_id = insert(cursor, "demo_method_version", method_id=method_id, taxonomy_id=taxonomy_id,
                            based_on_version_id=base["latest_version_id"], **revision_fields)
        for item in snapshot["stages"]:
            columns = {key: json_text(value) if key.endswith("_json") and value is not None else value
                       for key, value in item.items() if key != "model_revision_id"}
            stage_id = insert(cursor, "demo_method_stage", method_version_id=version_id, **columns)
            if item["model_revision_id"] is not None:
                insert(cursor, "demo_method_stage_model", method_stage_id=stage_id, model_role="chat",
                       model_connection_revision_id=item["model_revision_id"])
        number = one(cursor, "SELECT COALESCE(MAX(version_no), 0) + 1 AS n FROM demo_method_version WHERE method_id = %s",
                     (method_id,))["n"]
        cursor.execute("""
            UPDATE demo_method_version SET state = 'saved', version_no = %s, config_hash = %s,
                change_note = %s, validation_report_json = %s, saved_by = %s, saved_at = UTC_TIMESTAMP(6)
            WHERE id = %s
        """, (number, config_hash, "导入当前算法接口实现、固定知识版本和阶段模型修订。",
              json_text(report), owner, version_id))
        cursor.execute("""
            UPDATE demo_method SET latest_version_id = %s, default_version_id = COALESCE(default_version_id, %s)
            WHERE id = %s
        """, (version_id, version_id, method_id))
        version = one(cursor, "SELECT * FROM demo_method_version WHERE id = %s", (version_id,))
        base = one(cursor, "SELECT * FROM demo_method WHERE id = %s", (method_id,))
        action = "created"
    require(version is not None, f"{system}: saved method for the current configuration was not found")
    return action, base, version


def verify_method(cursor, system, base, version, snapshot, report, owner):
    version_id = version["id"]
    for field in ("knowledge_version_id", "implementation_key", "implementation_revision", "config_schema_version"):
        require(version[field] == snapshot[field], f"{system}: method version {field} does not match")
    require(version["saved_by"] == owner and json.loads(version["validation_report_json"]) == report,
            f"{system}: method version has incorrect save metadata")
    actual = rows(cursor, """
        SELECT s.stage_code, s.strategy_code, s.config_json, s.prompt_templates_json, s.call_options_json,
               s.output_schema_version, s.output_contract_json, sm.model_role,
               sm.model_connection_revision_id AS model_revision_id
        FROM demo_method_stage s LEFT JOIN demo_method_stage_model sm ON sm.method_stage_id = s.id
        WHERE s.method_version_id = %s
    """, (version_id,))
    by_stage = {item["stage_code"]: item for item in actual}
    require(len(actual) == len(by_stage) == len(snapshot["stages"]), f"{system}: incorrect stage or binding count")
    for expected in snapshot["stages"]:
        code = expected["stage_code"]
        persisted = by_stage.get(code)
        role = "chat" if expected["model_revision_id"] is not None else None
        require(persisted and persisted.pop("model_role") == role, f"{system}: invalid {code} model role")
        normalized = {key: json.loads(value) if key.endswith("_json") and value is not None else value
                      for key, value in persisted.items()}
        require(json_text(normalized) == json_text(expected), f"{system}: stored stage {code} differs from the source")
    return {
        "system": system, "method_id": base["id"], "version_id": version_id, "version_no": version["version_no"],
        "is_default": base["default_version_id"] == version_id, "is_latest": base["latest_version_id"] == version_id,
        "knowledge_version_id": version["knowledge_version_id"], "stage_count": len(actual),
        "fallback_first": json.loads(by_stage["recall"]["config_json"])["fallback_discipline_order"][0],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--algorithm-dir", type=Path, required=True)
    parser.add_argument("--database", choices=knowledge.DATABASES, default=knowledge.DATABASES[0])
    parser.add_argument("--model-connection-id", type=int, default=1)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true")
    mode.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    require(args.model_connection_id > 0, "A positive model connection ID is required")
    root = args.algorithm_dir.resolve(strict=True)
    source_hashes, stages = read_method(root)
    bundles = [knowledge.read_bundle(root, system, config) for system, config in knowledge.system_config(root).items()]
    if not (args.apply or args.verify):
        print(json_text({"mode": "preview", "implementation_sha256": json_hash(source_hashes),
                         "stage_codes": list(OUTPUT_FIELDS), "model_connection_id": args.model_connection_id,
                         "systems": [{"system": bundle.system, "summaries": len(bundle.summaries),
                                      "fallback_first": next(iter(bundle.summaries))} for bundle in bundles]}))
        return

    with knowledge.connect(args.database) as connection, connection.cursor() as cursor:
        require(one(cursor, """
            SELECT COUNT(*) AS applied FROM DATABASECHANGELOG
            WHERE ID = 'method-5.0.0-stage-model-delete-guard' AND EXECTYPE = 'EXECUTED'
        """)["applied"] == 1, "Apply the method Liquibase migrations first")
        if args.apply:
            require(one(cursor, "SELECT GET_LOCK(%s, 15) AS acquired", (f"{args.database}:method-import",))["acquired"] == 1,
                    "Another method import is running")
        connection.rollback()
        cursor.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED")
        connection.begin()
        owner = admin_id(cursor)
        model = model_revision(cursor, args.model_connection_id, owner)
        systems = []
        for bundle in bundles:
            snapshot, report = method_snapshot(cursor, bundle, stages, source_hashes, model)
            knowledge_base = knowledge.find_base(cursor, bundle)
            action, base, version = store_method(cursor, bundle.system, knowledge_base["taxonomy_id"], owner,
                                                 snapshot, report, args.apply)
            systems.append({"action": action, **verify_method(cursor, bundle.system, base, version, snapshot, report, owner)})
        if args.apply:
            connection.commit()
    print(json_text({"database": args.database, "owner": "admin", "model_connection_id": model["connection_id"],
                     "model_revision_id": model["revision_id"], "model_id": model["model_id"], "systems": systems}))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        raise SystemExit(str(error)) from error
