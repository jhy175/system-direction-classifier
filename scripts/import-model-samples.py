import argparse
import configparser
import hashlib
import hmac
import json
import logging
import os
import re
import sys
import time
from contextlib import closing
from pathlib import Path
from urllib.parse import urlsplit

import httpx
import pymysql
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from dotenv import dotenv_values
from openai import APIConnectionError, APIResponseValidationError, APIStatusError, APITimeoutError, OpenAI


PROJECT = Path(__file__).resolve().parents[1]
KEY_ID = "local-aes-gcm-v1"
KEY_PATH = PROJECT / ".local" / "model-secrets" / f"{KEY_ID}.bin"
INVALID_KEY = "invalid-model-connection-test-placeholder"
SAMPLES = (
    ("qwen-live", "百炼 Qwen（可用样本）", "使用业务空间凭据的真实连接。"),
    ("qwen-invalid-auth", "百炼 Qwen（无效 Key 对照）", "使用无效测试凭据，预期返回鉴权失败。"),
)


class ImportErrorMessage(Exception):
    pass


def load_source(env_file, expected_base_url):
    values = dotenv_values(env_file, encoding="utf-8-sig", interpolate=False)
    secret = (values.get("QWEN_API_KEY") or "").strip()
    base_url = (values.get("QWEN_BASE_URL") or "").strip().rstrip("/")
    model_id = (values.get("QWEN_MODEL") or "").strip()
    parsed = urlsplit(base_url)
    if (base_url != expected_base_url.strip().rstrip("/") or not base_url.isascii()
            or parsed.scheme != "https" or not parsed.hostname or parsed.username is not None
            or parsed.password is not None or parsed.query or parsed.fragment
            or parsed.path != "/compatible-mode/v1"):
        raise ImportErrorMessage("The .env Base URL must match the approved HTTPS compatible-mode endpoint.")
    if not secret or any(character.isspace() for character in secret):
        raise ImportErrorMessage("QWEN_API_KEY is missing or contains whitespace.")
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9._:/-]{0,254}", model_id) or model_id.startswith("sk-") or secret in model_id:
        raise ImportErrorMessage("QWEN_MODEL is missing or is not a supported model identifier.")
    return base_url, model_id, secret


def connect(database, settings):
    instance_file = configparser.ConfigParser()
    instance_file.read(PROJECT / ".tools" / "mysql" / "data" / "auto.cnf", encoding="utf-8-sig")
    connection = pymysql.connect(
        host="127.0.0.1", port=int(settings["mysqlPort"]), user="classifier_app",
        password=settings["dbPassword"], database=database, charset="utf8mb4", binary_prefix=True,
        cursorclass=pymysql.cursors.DictCursor, connect_timeout=5, read_timeout=15, write_timeout=15,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT @@server_uuid AS uuid, DATABASE() AS db")
            target = cursor.fetchone()
            if target["uuid"] != instance_file["auto"]["server-uuid"] or target["db"] != database:
                raise ImportErrorMessage("Connected MySQL instance does not match this project.")
    except Exception:
        connection.close()
        raise
    return connection


def scope_key(base_url):
    scope = ["v1", "openai_compatible", "dashscope", "bearer", base_url]
    return hashlib.sha256(json.dumps(scope, separators=(",", ":")).encode("ascii")).digest()


def associated_data(database, connection_id, scope, version):
    return f"{KEY_ID}:{database}:{connection_id}:{scope.hex()}:{version}".encode("ascii")


def decrypt(cipher, database, credential):
    if credential["encryption_key_id"] != KEY_ID or credential["encrypted_secret"] is None:
        raise ImportErrorMessage("The current credential requires a different key or secret store.")
    blob = credential["encrypted_secret"]
    aad = associated_data(database, credential["connection_id"], credential["credential_scope_key"],
                          credential["credential_version"])
    return cipher.decrypt(blob[:12], blob[12:], aad)


def seed_sample(cursor, database, cipher, owner_id, sample, base_url, model_id, secret):
    code, name, description = sample
    cursor.execute("""
        INSERT INTO demo_model_connection (sample_code, name, description, owner_user_id, created_by, updated_by)
        VALUES (%s, %s, %s, %s, %s, %s) ON DUPLICATE KEY UPDATE id = id
    """, (code, name, description, owner_id, owner_id, owner_id))
    cursor.execute("""
        SELECT id, owner_user_id, status, current_revision_id
        FROM demo_model_connection WHERE sample_code = %s FOR UPDATE
    """, (code,))
    connection = cursor.fetchone()
    if connection["owner_user_id"] != owner_id or connection["status"] != "enabled":
        raise ImportErrorMessage("An existing sample has a different owner or is disabled.")
    connection_id = connection["id"]
    scope = scope_key(base_url)
    cursor.execute("""
        SELECT id FROM demo_model_connection_revision
        WHERE id = %s AND connection_id = %s AND base_url = %s AND model_id = %s
          AND credential_scope_key = %s AND protocol_code = 'openai_compatible' AND provider_code = 'dashscope'
          AND auth_type = 'bearer' AND model_type = 'chat' AND adapter_options_json IS NULL AND routing_options_json IS NULL
    """, (connection["current_revision_id"], connection_id, base_url, model_id, scope))
    if cursor.fetchone() is None:
        cursor.execute("""
            INSERT INTO demo_model_connection_revision (connection_id, revision_no, protocol_code, provider_code,
                model_type, base_url, model_id, auth_type, credential_scope_key, created_by)
            SELECT %s, COALESCE(MAX(revision_no), 0) + 1, 'openai_compatible', 'dashscope', 'chat', %s, %s, 'bearer', %s, %s
            FROM demo_model_connection_revision WHERE connection_id = %s
        """, (connection_id, base_url, model_id, scope, owner_id, connection_id))
        cursor.execute("UPDATE demo_model_connection SET current_revision_id = %s, updated_by = %s WHERE id = %s",
                       (cursor.lastrowid, owner_id, connection_id))

    cursor.execute("""
        SELECT id, connection_id, credential_scope_key, credential_version, encryption_key_id, encrypted_secret
        FROM demo_model_credential WHERE connection_id = %s AND credential_scope_key = %s AND active_slot = 1
    """, (connection_id, scope))
    active = cursor.fetchone()
    secret_bytes = secret.encode("utf-8")
    if active is not None:
        if hmac.compare_digest(decrypt(cipher, database, active), secret_bytes):
            return
        cursor.execute("""
            UPDATE demo_model_credential SET status = 'revoked', revoked_at = CURRENT_TIMESTAMP(6),
                encrypted_secret = NULL, encryption_key_id = NULL WHERE id = %s
        """, (active["id"],))
    cursor.execute("""
        SELECT COALESCE(MAX(credential_version), 0) + 1 AS version FROM demo_model_credential
        WHERE connection_id = %s AND credential_scope_key = %s
    """, (connection_id, scope))
    version = cursor.fetchone()["version"]
    aad = associated_data(database, connection_id, scope, version)
    nonce = os.urandom(12)
    cursor.execute("""
        INSERT INTO demo_model_credential (connection_id, credential_scope_key, credential_version,
            encrypted_secret, encryption_key_id, status, created_by)
        VALUES (%s, %s, %s, %s, %s, 'active', %s)
    """, (connection_id, scope, version, nonce + cipher.encrypt(nonce, secret_bytes, aad), KEY_ID, owner_id))


def safe_identifier(value, secret, limit):
    if (isinstance(value, str) and len(value) <= limit and secret not in value
            and "sk-" not in value.lower() and re.fullmatch(r"[A-Za-z0-9_.:/-]+", value)):
        return value
    return None


def probe(base_url, model_id, secret):
    started = time.monotonic()
    result = {"reachable": None, "call_status": "failed", "http_status": None, "provider_request_id": None,
              "returned_model_id": None, "usage_json": None, "response_preview": None,
              "error_code": None, "error_message": None}
    try:
        with OpenAI(api_key=secret, base_url=base_url, timeout=60, max_retries=0,
                    http_client=httpx.Client(follow_redirects=False, trust_env=False)) as client:
            raw = client.chat.completions.with_raw_response.create(
                model=model_id, messages=[{"role": "user", "content": "只回复“连接成功”，不要添加其他内容。"}],
                temperature=0, max_tokens=16,
            )
            result.update(reachable=True, http_status=raw.status_code,
                          provider_request_id=safe_identifier(raw.headers.get("x-request-id"), secret, 160))
            response = raw.parse()
            content = response.choices[0].message.content if response.choices else None
            if not isinstance(content, str) or not content.strip():
                result.update(error_code="invalid_response", error_message="服务未返回可用的文本回复。")
            else:
                preview = content.strip()
                result.update(call_status="success", returned_model_id=safe_identifier(response.model, secret, 255),
                              response_preview=preview if preview in {"连接成功", "连接成功。"} else None)
            if response.usage is not None:
                usage = response.usage
                result["usage_json"] = json.dumps({"prompt_tokens": usage.prompt_tokens,
                                                  "completion_tokens": usage.completion_tokens,
                                                  "total_tokens": usage.total_tokens})
    except APIStatusError as exc:
        code, message = {
            401: ("auth_failed", "服务拒绝了模型凭据。"),
            403: ("auth_failed", "服务拒绝了模型调用权限。"),
            404: ("not_found", "服务地址或模型不可用。"),
            429: ("rate_limited", "服务限制了请求频率或额度。"),
        }.get(exc.status_code, ("http_error", "服务返回了 HTTP 错误。"))
        result.update(reachable=True, http_status=exc.status_code, error_code=code, error_message=message,
                      provider_request_id=safe_identifier(exc.response.headers.get("x-request-id"), secret, 160))
    except APITimeoutError:
        result.update(error_code="timeout", error_message="模型调用超时。")
    except APIConnectionError:
        result.update(reachable=False, error_code="connection_error", error_message="未能连接到服务。")
    except (APIResponseValidationError, AttributeError, TypeError, ValueError):
        result.update(call_status="failed", error_code="invalid_response", error_message="服务响应格式无法解析。")
    result["duration_ms"] = round((time.monotonic() - started) * 1000)
    return result


def test_sample(cursor, database, cipher, code, owner_id, base_url, model_id, source_secret):
    cursor.execute("""
        SELECT c.id AS connection_id, r.id AS revision_id, r.base_url, r.model_id, k.id AS credential_id,
            k.credential_scope_key, k.credential_version, k.encryption_key_id, k.encrypted_secret
        FROM demo_model_connection c
        JOIN demo_model_connection_revision r ON r.connection_id = c.id AND r.id = c.current_revision_id
        JOIN demo_model_credential k ON k.connection_id = c.id AND k.credential_scope_key = r.credential_scope_key
            AND k.active_slot = 1
        WHERE c.sample_code = %s AND c.owner_user_id = %s AND c.status = 'enabled'
          AND r.base_url = %s AND r.model_id = %s AND r.protocol_code = 'openai_compatible' AND r.auth_type = 'bearer'
    """, (code, owner_id, base_url, model_id))
    saved = cursor.fetchone()
    if saved is None:
        raise ImportErrorMessage("The committed sample could not be read back.")
    decrypted = decrypt(cipher, database, saved)
    if not hmac.compare_digest(decrypted, source_secret.encode("utf-8")):
        raise ImportErrorMessage("The committed credential does not match the import source.")
    result = probe(saved["base_url"], saved["model_id"], decrypted.decode("utf-8"))
    cursor.execute("""
        INSERT INTO demo_model_connection_test (connection_id, connection_revision_id, credential_scope_key,
            credential_id, tested_base_url, tested_by, reachable, call_status, http_status, duration_ms,
            provider_request_id, returned_model_id, usage_json, response_preview, error_code, error_message)
        VALUES (%(connection_id)s, %(revision_id)s, %(credential_scope_key)s, %(credential_id)s,
            %(base_url)s, %(owner_id)s, %(reachable)s, %(call_status)s, %(http_status)s, %(duration_ms)s,
            %(provider_request_id)s, %(returned_model_id)s, %(usage_json)s, %(response_preview)s, %(error_code)s, %(error_message)s)
    """, {**saved, **result, "owner_id": owner_id})
    return {"sample": code, "connection_id": saved["connection_id"], "revision_id": saved["revision_id"],
            "test_id": cursor.lastrowid, "status": result["call_status"], "http_status": result["http_status"],
            "error_code": result["error_code"], "duration_ms": result["duration_ms"]}


def main():
    logging.disable(logging.CRITICAL)
    parser = argparse.ArgumentParser(description="Import two DashScope samples and test their stored credentials.")
    parser.add_argument("--env-file", required=True, type=Path)
    parser.add_argument("--expected-base-url", required=True)
    parser.add_argument("--database", required=True, choices=("direction_classifier", "direction_classifier_test"))
    args = parser.parse_args()
    base_url, model_id, valid_key = load_source(args.env_file, args.expected_base_url)
    key = KEY_PATH.read_bytes()
    if len(key) != 32:
        raise ImportErrorMessage("The local model encryption key is invalid.")
    cipher = AESGCM(key)
    settings_path = PROJECT / ".local" / "user-module.properties"
    settings = dict(line.split("=", 1) for line in settings_path.read_text(encoding="utf-8-sig").splitlines()
                    if "=" in line)
    sample_keys = (valid_key, INVALID_KEY)

    with closing(connect(args.database, settings)) as connection, connection.cursor() as cursor:
        cursor.execute("SELECT id FROM demo_user WHERE login = %s AND status = 1", (settings["adminLogin"],))
        owner = cursor.fetchone()
        if owner is None:
            raise ImportErrorMessage("No active local bootstrap administrator found for sample ownership.")
        owner_id = owner["id"]
        for sample, secret in zip(SAMPLES, sample_keys):
            seed_sample(cursor, args.database, cipher, owner_id, sample, base_url, model_id, secret)
        connection.commit()

    outcomes = []
    for (code, _, _), secret in zip(SAMPLES, sample_keys):
        with closing(connect(args.database, settings)) as connection, connection.cursor() as cursor:
            outcome = test_sample(cursor, args.database, cipher, code, owner_id, base_url, model_id, secret)
            connection.commit()
            outcomes.append(outcome)
    print(json.dumps({"database": args.database, "model_id": model_id, "tests": outcomes}, ensure_ascii=False))
    return 0 if outcomes[0]["status"] == "success" and outcomes[1]["error_code"] == "auth_failed" else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ImportErrorMessage as exc:
        sys.exit(str(exc))
    except Exception as exc:
        code = exc.args[0] if isinstance(exc, pymysql.MySQLError) and exc.args and isinstance(exc.args[0], int) else None
        sys.exit(f"Sample import failed: {type(exc).__name__}" + (f" ({code})" if code is not None else ""))
