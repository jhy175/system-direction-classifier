package cn.edu.whu.irlab.systemdirectionclassifier.manager.model;

import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.CurrentActor;
import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityException;
import cn.edu.whu.irlab.systemdirectionclassifier.dao.IdentityDAO;
import cn.edu.whu.irlab.systemdirectionclassifier.manager.identity.IdentityManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static cn.edu.whu.irlab.systemdirectionclassifier.manager.model.ModelConnectionConfig.*;

@Component
@Profile("mysql")
@RequiredArgsConstructor
public class ModelConnectionManager {
    private static final String CONNECTION = "demo_model_connection";
    private static final String REVISION = "demo_model_connection_revision";
    private static final String CREDENTIAL = "demo_model_credential";
    private static final String TEST = "demo_model_connection_test";
    private static final String REVISION_COLUMNS = "r.id AS revision_id,r.revision_no,r.protocol_code,r.provider_code," +
        "r.model_type,r.base_url,r.model_id,r.auth_type,r.adapter_options_json,r.routing_options_json";
    private static final String CATALOG_SELECT =
        "SELECT c.id,c.name,c.description,c.owner_user_id,c.visibility,c.team_id,c.status,c.updated_at," +
        "DATE_FORMAT(c.updated_at,'%Y-%m-%dT%H:%i:%s.%f') AS edit_token,u.login AS owner_login," +
        "u.display_name AS owner_name,tm.name AS team_name," + REVISION_COLUMNS + "," +
        "(k.id IS NOT NULL) AS credential_configured,t.call_status AS last_call_status,t.tested_at AS last_tested_at," +
        "t.http_status AS last_http_status,t.error_code AS last_error_code " +
        "FROM demo_model_connection c JOIN demo_model_connection_revision r ";
    private static final String CATALOG_JOINS =
        "JOIN demo_user u ON u.id=c.owner_user_id LEFT JOIN demo_team tm ON tm.id=c.team_id " +
        "LEFT JOIN demo_model_credential k ON k.connection_id=c.id AND k.credential_scope_key=r.credential_scope_key " +
        "AND k.active_slot=1 AND r.auth_type<>'none' " +
        "LEFT JOIN demo_model_connection_test t ON t.id=(SELECT x.id FROM demo_model_connection_test x " +
        "WHERE x.connection_revision_id=r.id AND x.credential_id <=> k.id AND x.tested_base_url=r.base_url " +
        "ORDER BY x.tested_at DESC,x.id DESC LIMIT 1) ";
    private static final String CATALOG = CATALOG_SELECT + "ON r.id=c.current_revision_id " + CATALOG_JOINS;
    private static final String REVISION_CATALOG = CATALOG_SELECT + "ON r.connection_id=c.id " + CATALOG_JOINS;
    private static final String TEST_COLUMNS = "id,connection_revision_id,tested_base_url,tested_at,reachable,call_status," +
        "http_status,duration_ms,provider_request_id,returned_model_id,usage_json,response_preview,error_code,error_message";

    private final IdentityDAO db;
    private final IdentityManager identity;
    private final ObjectMapper json;
    private final ModelCredentialCipher cipher;
    private final ModelConnectionProbe probe;

    @Transactional(readOnly = true)
    public Map<String,Object> catalog(String search, String type, String status, int page) {
        Access access = access();
        if (page < 1 || page > 100000) throw invalid("无效页码");
        if (!Arrays.asList("", "chat", "embedding").contains(type) ||
            !Arrays.asList("", "enabled", "disabled").contains(status)) throw invalid("无效的筛选条件");
        String needle = search.trim().toLowerCase(Locale.ROOT);
        List<Map<String,Object>> rows = new ArrayList<>();
        for (Map<String,Object> row : db.query(CATALOG + "WHERE c.status<>'archived' ORDER BY c.updated_at DESC,c.id DESC")) {
            if (!access.reads(row) || (!type.isEmpty() && !type.equals(row.get("modelType"))) ||
                (!status.isEmpty() && !status.equals(row.get("status")))) continue;
            String label = (row.get("name") + " " + row.get("modelId") + " " + row.get("description")).toLowerCase(Locale.ROOT);
            if (label.contains(needle)) rows.add(decorate(row, access));
        }
        int start = Math.min(rows.size(), (page - 1) * 20);
        return fields("items", rows.subList(start, Math.min(rows.size(), start + 20)), "total", rows.size(),
            "page", page, "pageSize", 20, "canCreate", access.manage,
            "teams", db.query("SELECT id,name FROM demo_team WHERE status=1 ORDER BY name,id").stream()
                .filter(team -> access.teams.contains(id(team, "id"))).collect(Collectors.toList()));
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> options() {
        return options(Collections.emptySet());
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> options(Set<Long> pinnedRevisions) {
        Access access = access();
        String extra = pinnedRevisions.isEmpty() ? "" : " OR r.id IN (" +
            String.join(",", Collections.nCopies(pinnedRevisions.size(), "?")) + ")";
        return db.query(REVISION_CATALOG + "WHERE c.status='enabled' AND (r.id=c.current_revision_id" + extra +
            ") ORDER BY c.name,c.id,r.revision_no DESC", pinnedRevisions.toArray()).stream()
            .filter(row -> access.uses(row) && ("none".equals(row.get("authType")) || truth(row.get("credentialConfigured"))))
            .filter(row -> !"failed".equals(row.get("lastCallStatus")))
            .map(row -> fields("revisionId", row.get("revisionId"), "revisionNo", row.get("revisionNo"),
                "name", row.get("name"), "modelId", row.get("modelId"), "modelType", row.get("modelType")))
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Map<String,Object> detail(long id) {
        Access access = access();
        return detail(connection(id, access), access);
    }

    @Transactional
    public Map<String,Object> create(JsonNode input) {
        Access access = access();
        requireManage(access);
        ModelConnectionConfig config = input(input, json);
        config.endpoints.forEach(probe::validateAddress);
        String key = apiKey(input, config);
        Map<String,Object> metadata = metadata(input, access);
        metadata.put("owner_user_id", access.user);
        metadata.put("created_by", access.user);
        long id = db.insert(CONNECTION, metadata);
        byte[] scope = config.scope(json);
        saveRevision(id, config, scope, access.user);
        if (key != null) rotate(id, scope, key, access.user);
        return detail(connection(id, access), access);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> update(long id, JsonNode input) {
        Access access = access();
        Map<String,Object> row = locked(id, access, input);
        ModelConnectionConfig current = stored(row, json);
        ModelConnectionConfig config = input(input, json);
        config.endpoints.forEach(probe::validateAddress);
        String key = apiKey(input, config);
        byte[] scope;
        if (!current.same(config)) {
            scope = config.scope(json);
            saveRevision(id, config, scope, access.user);
        } else {
            scope = (byte[])db.one("SELECT credential_scope_key FROM demo_model_connection_revision WHERE id=?",
                id(row, "revisionId")).get("credentialScopeKey");
        }
        if (key != null) rotate(id, scope, key, access.user);
        Map<String,Object> values = metadata(input, access);
        db.update(CONNECTION, id, values);
        touch(id, access.user);
        return detail(connection(id, access), access);
    }

    @Transactional
    public Map<String,Object> copy(long source, JsonNode input) {
        Access access = access();
        requireManage(access);
        ModelConnectionConfig config = stored(connection(source, access), json);
        String name = text(input, "name", 120, true);
        long id = db.insert(CONNECTION, fields("name", name, "owner_user_id", access.user,
            "created_by", access.user, "updated_by", access.user));
        saveRevision(id, config, config.scope(json), access.user);
        return detail(connection(id, access), access);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> status(long id, JsonNode input) {
        Access access = access();
        locked(id, access, input);
        String status = choice(input, "status", "enabled", "disabled");
        db.execute("UPDATE demo_model_connection SET status=?,updated_by=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
            status, access.user, id);
        return detail(connection(id, access), access);
    }

    // Do not hold a database transaction or lock during the remote request.
    public Map<String,Object> test(long connectionId, JsonNode input) {
        Access access = access();
        Map<String,Object> row = connection(connectionId, access);
        if (!access.uses(row)) throw denied();
        if (!"enabled".equals(row.get("status"))) throw conflict("请先启用该连接");
        long revision = id(row, "revisionId");
        JsonNode requestedRevision = input.path("revisionId");
        if (!requestedRevision.isIntegralNumber() || !requestedRevision.canConvertToLong() ||
            requestedRevision.longValue() != revision) throw conflict("模型配置已变化，请刷新后重试");
        ModelConnectionConfig config = stored(row, json);
        String address = text(input, "baseUrl", 2048, false);
        address = address == null ? config.baseUrl : url(address);
        if (!config.endpoints.contains(address)) throw invalid("请选择该修订中的服务地址");
        Map<String,Object> target = db.one("SELECT r.credential_scope_key,k.id AS credential_id,k.credential_version," +
            "k.encrypted_secret,k.encryption_key_id,DATABASE() AS schema_name FROM demo_model_connection c " +
            "JOIN demo_model_connection_revision r ON r.id=c.current_revision_id " +
            "LEFT JOIN demo_model_credential k ON k.connection_id=c.id AND k.credential_scope_key=r.credential_scope_key " +
            "AND k.active_slot=1 AND r.auth_type<>'none' WHERE c.id=? AND r.id=? AND c.status='enabled'", connectionId, revision);
        if (target == null) throw conflict("模型配置已变化，请刷新后重试");
        byte[] scope = (byte[])target.get("credentialScopeKey");
        Long credential = id(target, "credentialId");
        ModelConnectionProbe.Result result;
        if (!"none".equals(config.auth) && credential == null) {
            result = new ModelConnectionProbe.Result().fail("missing_credential", "请先配置该地址的 API Key");
        } else {
            String key = credential == null ? null : cipher.decrypt((String)target.get("schemaName"), connectionId, scope,
                id(target, "credentialVersion"), (String)target.get("encryptionKeyId"), (byte[])target.get("encryptedSecret"));
            result = probe.run(config, address, key);
        }
        long test = db.insert(TEST, fields("connection_id", connectionId, "connection_revision_id", revision,
            "credential_scope_key", scope, "credential_id", credential, "tested_base_url", address, "tested_by", access.user,
            "reachable", result.reachable, "call_status", result.success ? "success" : "failed", "http_status", result.httpStatus,
            "duration_ms", result.durationMs, "provider_request_id", result.requestId, "returned_model_id", result.returnedModel,
            "usage_json", result.usageJson, "response_preview", result.preview, "error_code", result.errorCode,
            "error_message", result.errorMessage));
        return db.one("SELECT " + TEST_COLUMNS + " FROM demo_model_connection_test WHERE id=?", test);
    }

    private Map<String,Object> metadata(JsonNode input, Access access) {
        String visibility = choice(input, "visibility", "private", "team");
        JsonNode team = input.path("teamId");
        Long teamId = null;
        if ("team".equals(visibility)) {
            if (!team.isIntegralNumber() || !team.canConvertToLong() || !access.teams.contains(team.longValue())) {
                throw invalid("请选择你所在的有效团队");
            }
            teamId = team.longValue();
        }
        return fields("name", text(input, "name", 120, true), "description", text(input, "description", 1000, false),
            "visibility", visibility, "team_id", teamId, "updated_by", access.user);
    }

    private static String apiKey(JsonNode input, ModelConnectionConfig config) {
        String key = text(input, "apiKey", 4096, false);
        if (key != null && (!"bearer".equals(config.auth) || !key.matches("[!-~]+") || key.contains("*"))) {
            throw invalid("请填写完整的 API Key，或留空以保留同一地址范围的凭据");
        }
        return key;
    }

    private void saveRevision(long id, ModelConnectionConfig config, byte[] scope, long actor) {
        long number = db.count("SELECT COALESCE(MAX(revision_no),0)+1 FROM demo_model_connection_revision WHERE connection_id=?", id);
        long revision = db.insert(REVISION, fields("connection_id", id, "revision_no", number,
            "protocol_code", config.protocol, "provider_code", config.provider, "model_type", config.type,
            "base_url", config.baseUrl, "model_id", config.model, "auth_type", config.auth, "credential_scope_key", scope,
            "adapter_options_json", config.adapter == null ? null : config.adapter.toString(),
            "routing_options_json", config.routing == null ? null : config.routing.toString(), "created_by", actor));
        db.execute("UPDATE demo_model_connection SET current_revision_id=?,updated_by=? WHERE id=?", revision, actor, id);
    }

    private void rotate(long connection, byte[] scope, String key, long actor) {
        long version = db.count("SELECT COALESCE(MAX(credential_version),0)+1 FROM demo_model_credential " +
            "WHERE connection_id=? AND credential_scope_key=?", connection, scope);
        String database = (String)db.one("SELECT DATABASE() AS schema_name").get("schemaName");
        byte[] encrypted = cipher.encrypt(database, connection, scope, version, key);
        db.execute("UPDATE demo_model_credential SET status='revoked',revoked_at=CURRENT_TIMESTAMP(6)," +
            "encrypted_secret=NULL,encryption_key_id=NULL,secret_ref=NULL " +
            "WHERE connection_id=? AND credential_scope_key=? AND active_slot=1", connection, scope);
        db.insert(CREDENTIAL, fields("connection_id", connection, "credential_scope_key", scope, "credential_version", version,
            "encrypted_secret", encrypted, "encryption_key_id", ModelCredentialCipher.KEY_ID, "status", "active", "created_by", actor));
    }

    private void touch(long id, long actor) {
        db.execute("UPDATE demo_model_connection SET updated_at=CURRENT_TIMESTAMP(6),updated_by=? WHERE id=?", actor, id);
    }

    private Map<String,Object> detail(Map<String,Object> row, Access access) {
        long id = id(row, "id");
        row.put("revisions", db.query("SELECT " + REVISION_COLUMNS + ",r.created_at FROM demo_model_connection_revision r " +
            "WHERE r.connection_id=? ORDER BY r.revision_no DESC", id));
        row.put("tests", db.query("SELECT " + TEST_COLUMNS + " FROM demo_model_connection_test WHERE connection_id=? " +
            "ORDER BY tested_at DESC,id DESC LIMIT 30", id));
        return decorate(row, access);
    }

    private Map<String,Object> decorate(Map<String,Object> row, Access access) {
        row.put("credentialConfigured", truth(row.get("credentialConfigured")));
        row.put("canManage", access.manage && access.owner(row));
        row.put("canTest", access.uses(row) && "enabled".equals(row.get("status")));
        return row;
    }

    private Map<String,Object> locked(long id, Access access, JsonNode input) {
        requireManage(access);
        if (db.one("SELECT id FROM demo_model_connection WHERE id=? FOR UPDATE", id) == null) throw missing();
        Map<String,Object> row = connection(id, access);
        if (!access.owner(row)) throw denied();
        if (!Objects.equals(row.get("editToken"), text(input, "editToken", 40, true))) {
            throw conflict("连接已被更新，请刷新后重试");
        }
        return row;
    }

    private Map<String,Object> connection(long id, Access access) {
        Map<String,Object> row = db.one(CATALOG + "WHERE c.id=? AND c.status<>'archived'", id);
        if (row == null || !access.reads(row)) throw missing();
        return row;
    }

    private Access access() {
        long actor = CurrentActor.id();
        Set<String> permissions = identity.effectivePermissions(actor).stream().map(p -> (String)p.get("code"))
            .collect(Collectors.toSet());
        if (!permissions.contains("connection:use")) throw denied();
        Set<Long> teams = db.query("SELECT m.team_id FROM demo_team_member m JOIN demo_team t ON t.id=m.team_id " +
            "WHERE m.user_id=? AND t.status=1 AND m.status=1 AND m.member_role IN ('member','admin')", actor).stream()
            .map(row -> id(row, "teamId")).collect(Collectors.toSet());
        Set<Long> readable = new HashSet<>();
        Set<Long> usable = new HashSet<>();
        for (Map<String,Object> grant : db.query("SELECT resource_id,principal_id,principal_type,capability " +
            "FROM demo_resource_grant WHERE resource_type='model_connection' AND revoked_at IS NULL")) {
            Long principal = id(grant, "principalId");
            if (!("user".equals(grant.get("principalType")) && principal.equals(actor)) &&
                !("team".equals(grant.get("principalType")) && teams.contains(principal))) continue;
            Long resource = id(grant, "resourceId");
            if ("use".equals(grant.get("capability"))) usable.add(resource);
            if ("read".equals(grant.get("capability"))) readable.add(resource);
        }
        return new Access(actor, permissions.contains("connection:manage"), teams, readable, usable);
    }

    private static Long id(Map<String,Object> row, String name) {
        Object value = row.get(name);
        return value == null ? null : ((Number)value).longValue();
    }

    private static boolean truth(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof Number && ((Number)value).intValue() != 0);
    }

    private static Map<String,Object> fields(Object... pairs) {
        Map<String,Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String)pairs[i], pairs[i + 1]);
        return values;
    }

    private static void requireManage(Access access) { if (!access.manage) throw denied(); }
    private static IdentityException denied() { return new IdentityException(403, "没有该模型连接操作权限"); }
    private static IdentityException missing() { return new IdentityException(404, "模型连接不存在或不可见"); }
    private static IdentityException conflict(String message) { return new IdentityException(409, message); }

    private static final class Access {
        final Long user;
        final boolean manage;
        final Set<Long> teams, readable, usable;

        Access(long user, boolean manage, Set<Long> teams, Set<Long> readable, Set<Long> usable) {
            this.user = user;
            this.manage = manage;
            this.teams = teams;
            this.readable = readable;
            this.usable = usable;
        }

        boolean owner(Map<String,Object> row) { return user.equals(id(row, "ownerUserId")); }
        boolean reads(Map<String,Object> row) { return uses(row) || readable.contains(id(row, "id")); }
        boolean uses(Map<String,Object> row) {
            return owner(row) || "builtin".equals(row.get("visibility"))
                || ("team".equals(row.get("visibility")) && teams.contains(id(row, "teamId")))
                || usable.contains(id(row, "id"));
        }
    }
}
