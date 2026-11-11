package cn.edu.whu.irlab.systemdirectionclassifier.manager.method;

import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.CurrentActor;
import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityException;
import cn.edu.whu.irlab.systemdirectionclassifier.dao.IdentityDAO;
import cn.edu.whu.irlab.systemdirectionclassifier.manager.identity.IdentityManager;
import cn.edu.whu.irlab.systemdirectionclassifier.manager.knowledge.KnowledgeManager;
import cn.edu.whu.irlab.systemdirectionclassifier.manager.model.ModelConnectionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static cn.edu.whu.irlab.systemdirectionclassifier.manager.method.MethodConfiguration.*;

@Component
@Profile("mysql")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MethodManager {
    private static final int PAGE_SIZE = 20;
    private static final String METHOD = "demo_method";
    private static final String VERSION = "demo_method_version";
    private static final String STAGE = "demo_method_stage";
    private static final String BINDING = "demo_method_stage_model";
    private static final String BASE_QUERY =
        "SELECT m.*,t.code AS taxonomy_code,t.name AS taxonomy_name,u.login AS owner_login," +
        "u.display_name AS owner_name,tm.name AS team_name,lv.version_no AS latest_number,dv.version_no AS default_number," +
        "kb.name AS knowledge_name,kv.version_no AS knowledge_version_no " +
        "FROM demo_method m JOIN demo_taxonomy t ON t.id=m.taxonomy_id JOIN demo_user u ON u.id=m.owner_user_id " +
        "LEFT JOIN demo_team tm ON tm.id=m.team_id LEFT JOIN demo_method_version lv ON lv.id=m.latest_version_id " +
        "LEFT JOIN demo_method_version dv ON dv.id=m.default_version_id " +
        "LEFT JOIN demo_knowledge_version kv ON kv.id=COALESCE(dv.knowledge_version_id,lv.knowledge_version_id) " +
        "LEFT JOIN demo_knowledge_base kb ON kb.id=kv.knowledge_base_id ";
    private static final String VERSION_QUERY =
        "SELECT v.id,v.method_id,v.taxonomy_id,v.state,v.version_no,v.revision,v.based_on_version_id," +
        "v.knowledge_version_id,v.implementation_key,v.implementation_revision,v.config_schema_version,v.config_hash," +
        "v.change_note,v.saved_at,u.login AS saved_by_login,kv.version_no AS knowledge_version_no," +
        "kv.knowledge_base_id,kb.name AS knowledge_name,parent.version_no AS based_on_number " +
        "FROM demo_method_version v LEFT JOIN demo_user u ON u.id=v.saved_by " +
        "LEFT JOIN demo_method_version parent ON parent.id=v.based_on_version_id " +
        "LEFT JOIN demo_knowledge_version kv ON kv.id=v.knowledge_version_id " +
        "LEFT JOIN demo_knowledge_base kb ON kb.id=kv.knowledge_base_id ";

    private final IdentityDAO db;
    private final IdentityManager identity;
    private final KnowledgeManager knowledge;
    private final ModelConnectionManager models;
    private final ObjectMapper json;

    public Map<String,Object> catalog(String search, String taxonomy, String scope, boolean archived, int page) {
        Access access = access("method:read");
        if (page < 1 || page > 100000) throw invalid("无效页码");
        if (!Arrays.asList("", "mine", "team", "builtin").contains(scope)) throw invalid("无效的筛选范围");
        String needle = search.trim().toLowerCase(Locale.ROOT);
        List<Map<String,Object>> rows = new ArrayList<>();
        for (Map<String,Object> row : db.query(BASE_QUERY + "ORDER BY m.updated_at DESC,m.id DESC")) {
            if (!access.reads(row) || archived != (row.get("archivedAt") != null) || (archived && !access.owns(row))) continue;
            if (!taxonomy.isEmpty() && !taxonomy.equals(row.get("taxonomyCode"))) continue;
            if ("mine".equals(scope) && !access.owns(row)) continue;
            if ("team".equals(scope) && !"team".equals(row.get("visibility"))) continue;
            if ("builtin".equals(scope) && !"builtin".equals(row.get("visibility"))) continue;
            String title = row.get("name") + " " + Objects.toString(row.get("description"), "");
            if (!needle.isEmpty() && !title.toLowerCase(Locale.ROOT).contains(needle)) continue;
            decorate(row, access);
            rows.add(row);
        }
        int start = Math.min(rows.size(), (page - 1) * PAGE_SIZE);
        return fields("items", rows.subList(start, Math.min(rows.size(), start + PAGE_SIZE)), "total", rows.size(),
            "page", page, "pageSize", PAGE_SIZE, "canCreate", access.has("method:create"),
            "taxonomies", db.query("SELECT id,code,name FROM demo_taxonomy ORDER BY id"), "teams", teams(access));
    }

    public List<Map<String,Object>> options() {
        Access access = access("method:read");
        Map<Long,List<Map<String,Object>>> versions = db.query(VERSION_QUERY +
            "WHERE v.state='saved' ORDER BY v.version_no DESC").stream().collect(Collectors.groupingBy(row -> id(row, "methodId")));
        List<Map<String,Object>> result = new ArrayList<>();
        for (Map<String,Object> row : db.query(BASE_QUERY + "WHERE m.archived_at IS NULL AND m.latest_version_id IS NOT NULL ORDER BY m.updated_at DESC,m.id DESC")) {
            if (!access.uses(row)) continue;
            long method = id(row, "id");
            List<Map<String,Object>> saved = versions.getOrDefault(method, Collections.emptyList());
            result.add(fields("id", method, "name", row.get("name"), "defaultVersionId", row.get("defaultVersionId"),
                "latestVersionId", row.get("latestVersionId"), "versions", saved));
        }
        return result;
    }

    public Map<String,Object> dependencies(long taxonomyId, Long methodId) {
        Access access = access("method:read");
        Set<Long> pinned = Collections.emptySet();
        if (methodId != null) {
            Map<String,Object> method = base(methodId, access, false);
            if (!Objects.equals(id(method, "taxonomyId"), taxonomyId) || !access.edits(method)) throw denied();
            Long draftId = id(method, "currentDraftId");
            if (draftId != null) pinned = pinned(stages(draftId));
        } else if (!access.has("method:create")) {
            throw denied();
        }
        List<Map<String,Object>> knowledgeVersions = access.has("knowledge:read")
            ? knowledge.methodVersions(taxonomyId) : Collections.emptyList();
        return fields("knowledgeVersions", knowledgeVersions, "models", modelOptions(access, pinned));
    }

    public Map<String,Object> detail(long methodId) {
        Access access = access("method:read");
        return detail(base(methodId, access, false), access);
    }

    public Map<String,Object> version(long methodId, long versionId) {
        Access access = access("method:read");
        Map<String,Object> method = base(methodId, access, false);
        Map<String,Object> row = versionRow(method, versionId, access);
        row.put("stages", stages(versionId).values().stream().map(Stage::view).collect(Collectors.toList()));
        return row;
    }

    public Map<String,Object> executionVersion(long methodId, long versionId) {
        Access access = access("debug:run");
        Map<String,Object> method = base(methodId, access, false);
        if (method.get("archivedAt") != null || !access.uses(method)) throw denied();
        Map<String,Object> version = saved(method, versionId, access);
        if (!IMPLEMENTATION.equals(version.get("implementationKey")) || !SCHEMA.equals(version.get("configSchemaVersion"))) {
            throw conflict("该方法版本的算法实现暂不可执行");
        }
        Map<String,Object> resources = knowledge.methodResources(id(version, "taxonomyId"), id(version, "knowledgeVersionId"));
        version.put("methodName", method.get("name"));
        version.put("manifestHash", resources.get("manifestHash"));
        version.put("stages", stages(versionId).values().stream().map(Stage::view).collect(Collectors.toList()));
        return version;
    }

    @Transactional
    public Map<String,Object> create(JsonNode input) {
        Access access = access("method:create");
        long taxonomy = requiredId(input, "taxonomyId");
        if (db.one("SELECT id FROM demo_taxonomy WHERE id=?", taxonomy) == null) throw invalid("请选择已有学科体系");
        Long knowledgeVersion = optionalId(input, "knowledgeVersionId");
        checkKnowledge(access, taxonomy, knowledgeVersion);
        Map<String,Object> template = db.one(VERSION_QUERY + "JOIN demo_method m ON m.id=v.method_id " +
            "WHERE m.visibility='builtin' AND v.state='saved' AND v.implementation_key=? AND v.config_schema_version=? " +
            "ORDER BY (m.taxonomy_id=?) DESC,v.version_no,v.id LIMIT 1", IMPLEMENTATION, SCHEMA, taxonomy);
        if (template == null) throw conflict("当前数据库尚未导入识别方法模板");
        long method = createBase(taxonomy, access.user, text(input, "name", 255, true), text(input, "description", 1000, false), null);
        createDraft(method, taxonomy, null, knowledgeVersion, template, access, true);
        return detail(base(method, access, false), access);
    }

    @Transactional
    public Map<String,Object> copy(long methodId, long versionId, JsonNode input) {
        Access access = access("method:create");
        Map<String,Object> source = base(methodId, access, false);
        Map<String,Object> saved = saved(source, versionId, access);
        long taxonomy = id(source, "taxonomyId");
        long method = createBase(taxonomy, access.user, text(input, "name", 255, true),
            (String)source.get("description"), versionId);
        createDraft(method, taxonomy, null, id(saved, "knowledgeVersionId"), saved, access, false);
        return detail(base(method, access, false), access);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> createDraft(long methodId, JsonNode input) {
        Access access = access("method:edit");
        Map<String,Object> method = editable(methodId, access, true);
        if (method.get("currentDraftId") != null) throw conflict("已有草稿，请继续编辑现有草稿");
        long versionId = requiredId(input, "versionId");
        Map<String,Object> source = saved(method, versionId, access);
        createDraft(methodId, id(method, "taxonomyId"), versionId, id(source, "knowledgeVersionId"), source, access, false);
        return detail(base(methodId, access, false), access);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> update(long methodId, JsonNode input) {
        Access access = access("method:edit");
        Map<String,Object> row = owned(methodId, access);
        String visibility = text(input, "visibility", 16, true);
        Long team = null;
        if ("builtin".equals(row.get("visibility"))) {
            if (!"builtin".equals(visibility)) throw invalid("内置方法的共享范围固定");
        } else if ("team".equals(visibility)) {
            team = requiredId(input, "teamId");
            if (!access.teams.contains(team)) throw denied();
        } else if (!"private".equals(visibility)) {
            throw invalid("请选择个人或团队共享");
        }
        db.update(METHOD, methodId, fields("name", text(input, "name", 255, true),
            "description", text(input, "description", 1000, false), "visibility", visibility, "team_id", team));
        return detail(base(methodId, access, false), access);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> archive(long methodId, JsonNode input) {
        Access access = access("method:edit");
        owned(methodId, access);
        if (!input.path("archived").isBoolean()) throw invalid("请提供归档状态");
        db.execute("UPDATE demo_method SET archived_at=" + (input.path("archived").asBoolean() ? "UTC_TIMESTAMP(6)" : "NULL") +
            " WHERE id=?", methodId);
        return detail(base(methodId, access, false), access);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> setDefault(long methodId, long versionId) {
        Access access = access("method:version:create");
        Map<String,Object> method = owned(methodId, access);
        if (method.get("archivedAt") != null) throw conflict("请先恢复已归档方法");
        saved(method, versionId, access);
        db.update(METHOD, methodId, fields("default_version_id", versionId));
        return detail(base(methodId, access, false), access);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> saveDraft(long methodId, JsonNode input) {
        Access access = access("method:edit");
        Map<String,Object> method = editable(methodId, access, true);
        Draft draft = draft(method, input, access, true);
        checkChangedKnowledge(draft, access);
        persist(draft);
        db.execute("UPDATE demo_method SET updated_at=UTC_TIMESTAMP(6) WHERE id=?", methodId);
        return fields("method", detail(base(methodId, access, false), access), "version", version(methodId, draft.id));
    }

    public Map<String,Object> validateDraft(long methodId, JsonNode input) {
        Access access = access("method:edit");
        Map<String,Object> report = validate(draft(editable(methodId, access, false), input, access, false), access);
        return fields("valid", report.get("valid"), "issues", report.get("issues"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> publish(long methodId, JsonNode input) {
        Access access = access("method:version:create");
        Map<String,Object> method = editable(methodId, access, true);
        if (!access.publishes(method)) throw denied();
        String note = text(input, "changeNote", 1000, true);
        Draft draft = draft(method, input, access, true);
        Map<String,Object> report = validate(draft, access);
        if (!Boolean.TRUE.equals(report.get("valid"))) return fields("valid", false, "issues", report.get("issues"));

        JsonNode order = json.valueToTree(report.remove("fallbackOrder"));
        draft.stages.get("recall").config.set("fallback_discipline_order", order);
        persist(draft);
        long next = db.count("SELECT COALESCE(MAX(version_no),0)+1 FROM demo_method_version WHERE method_id=?", methodId);
        String fingerprint = hash(json, fields("implementation_key", draft.row.get("implementationKey"),
            "implementation_revision", draft.row.get("implementationRevision"), "config_schema_version", draft.row.get("configSchemaVersion"),
            "knowledge_version_id", draft.knowledgeVersion, "knowledge_manifest_sha256", report.get("knowledgeManifestSha256"),
            "stages", draft.stages.values().stream().map(Stage::fingerprint).collect(Collectors.toList())));
        db.update(METHOD, methodId, fields("current_draft_id", null));
        db.execute("UPDATE demo_method_version SET state='saved',version_no=?,change_note=?,config_hash=?," +
            "validation_report_json=?,saved_by=?,saved_at=UTC_TIMESTAMP(6) WHERE id=?", next, note, fingerprint,
            json.valueToTree(report).toString(), access.user, draft.id);
        db.execute("UPDATE demo_method SET latest_version_id=?,default_version_id=COALESCE(default_version_id,?) WHERE id=?",
            draft.id, draft.id, methodId);
        return fields("method", detail(base(methodId, access, false), access), "version", version(methodId, draft.id));
    }

    private Map<String,Object> validate(Draft draft, Access access) {
        List<Map<String,Object>> issues = MethodConfiguration.validate(draft.stages, draft.availableModels);
        if (!IMPLEMENTATION.equals(draft.row.get("implementationKey")) || !SCHEMA.equals(draft.row.get("configSchemaVersion"))) {
            issue(issues, null, "implementation", "当前系统尚不支持该算法实现");
        }
        Map<String,Object> resources = null;
        if (draft.knowledgeVersion == null || !access.has("knowledge:read")) {
            issue(issues, null, "knowledgeVersionId", "请选择可使用的已保存知识版本");
        } else {
            try {
                resources = knowledge.methodResources(draft.taxonomy, draft.knowledgeVersion);
                JsonNode validation = json.valueToTree(resources.get("validation"));
                if (!validation.path("compatible").asBoolean()) validation.path("issues").forEach(node ->
                    issue(issues, null, "knowledgeVersionId", node.asText()));
            } catch (IdentityException error) {
                if (error.getStatus() != 404 && error.getStatus() != 403) throw error;
                issue(issues, null, "knowledgeVersionId", "知识版本不可使用或学科体系不匹配");
            }
        }
        return fields("valid", issues.isEmpty(), "issues", issues,
            "knowledgeManifestSha256", resources == null ? null : resources.get("manifestHash"),
            "fallbackOrder", resources == null ? null : resources.get("fallbackOrder"));
    }

    private void checkChangedKnowledge(Draft draft, Access access) {
        if (!Objects.equals(draft.knowledgeVersion, id(draft.row, "knowledgeVersionId"))) {
            checkKnowledge(access, draft.taxonomy, draft.knowledgeVersion);
        }
    }

    private void checkKnowledge(Access access, long taxonomy, Long version) {
        if (version == null) return;
        if (!access.has("knowledge:read") || knowledge.methodVersions(taxonomy).stream().noneMatch(row -> version.equals(id(row, "id")))) {
            throw invalid("请选择同一学科体系下有权使用的已保存知识版本");
        }
    }

    private Draft draft(Map<String,Object> method, JsonNode input, Access access, boolean lock) {
        keys(input, new HashSet<>(Arrays.asList("draftId", "revision", "knowledgeVersionId", "stageChanges", "changeNote")));
        long draftId = requiredId(input, "draftId");
        JsonNode revision = input.path("revision");
        if (!revision.isIntegralNumber() || !revision.canConvertToLong() || revision.asLong() < 0) throw invalid("无效的草稿修订号");
        if (!Objects.equals(id(method, "currentDraftId"), draftId)) throw conflict("草稿已变化，请重新打开方法");
        Map<String,Object> row = db.find(VERSION, draftId, lock);
        if (row == null || !"draft".equals(row.get("state")) || !Objects.equals(id(row, "revision"), revision.asLong())) {
            throw conflict("草稿已被更新，请刷新页面后重试");
        }
        Map<String,Stage> stages = stages(draftId);
        Set<Long> allowed = chatModels(access, pinned(stages));
        apply(input, stages, allowed);
        Long knowledgeVersion = input.has("knowledgeVersionId") ? optionalId(input, "knowledgeVersionId") : id(row, "knowledgeVersionId");
        return new Draft(draftId, id(method, "taxonomyId"), knowledgeVersion, row, stages, allowed);
    }

    private void persist(Draft draft) {
        db.execute("UPDATE demo_method_version SET knowledge_version_id=?,revision=revision+1 WHERE id=?",
            draft.knowledgeVersion, draft.id);
        for (Stage stage : draft.stages.values()) {
            db.update(STAGE, stage.id, fields("config_json", serialized(stage.config),
                "prompt_templates_json", serialized(stage.prompts), "call_options_json", serialized(stage.call)));
            if (Objects.equals(stage.originalModel, stage.model)) continue;
            db.execute("DELETE FROM demo_method_stage_model WHERE method_stage_id=? AND model_role='chat'", stage.id);
            bind(stage.id, stage.model);
        }
    }

    private long createBase(long taxonomy, long owner, String name, String description, Long source) {
        return db.insert(METHOD, fields("taxonomy_id", taxonomy, "owner_user_id", owner, "name", name,
            "description", description, "copied_from_version_id", source));
    }

    private void createDraft(long method, long taxonomy, Long parent, Long knowledgeVersion,
                             Map<String,Object> template, Access access, boolean requireUsableBindings) {
        long draftId = db.insert(VERSION, fields("method_id", method, "taxonomy_id", taxonomy,
            "knowledge_version_id", knowledgeVersion, "based_on_version_id", parent, "implementation_key", template.get("implementationKey"),
            "implementation_revision", template.get("implementationRevision"), "config_schema_version", template.get("configSchemaVersion")));
        Map<String,Stage> stages = stages(id(template, "id"));
        Set<Long> usable = requireUsableBindings ? chatModels(access, pinned(stages)) : Collections.emptySet();
        for (Stage stage : stages.values()) {
            long stageId = db.insert(STAGE, fields("method_version_id", draftId, "stage_code", stage.code,
                "strategy_code", stage.strategy, "config_json", serialized(stage.config), "prompt_templates_json", serialized(stage.prompts),
                "call_options_json", serialized(stage.call), "output_schema_version", stage.outputSchema,
                "output_contract_json", serialized(stage.output)));
            if (!requireUsableBindings || usable.contains(stage.model)) bind(stageId, stage.model);
        }
        db.update(METHOD, method, fields("current_draft_id", draftId));
    }

    private void bind(long stage, Long model) {
        if (model != null) db.insert(BINDING, fields("method_stage_id", stage, "model_role", "chat", "model_connection_revision_id", model));
    }

    private Map<String,Stage> stages(long version) {
        Map<String,Stage> ordered = new LinkedHashMap<>();
        for (Map<String,Object> row : db.query("SELECT s.*,b.model_connection_revision_id AS model_revision_id," +
            "CONCAT(c.name,' · ',r.model_id,' · 修订 ',r.revision_no) AS model_label FROM demo_method_stage s " +
            "LEFT JOIN demo_method_stage_model b ON b.method_stage_id=s.id AND b.model_role='chat' " +
            "LEFT JOIN demo_model_connection_revision r ON r.id=b.model_connection_revision_id " +
            "LEFT JOIN demo_model_connection c ON c.id=r.connection_id WHERE s.method_version_id=? " +
            "ORDER BY FIELD(s.stage_code,'parse','router','recall','judge','post','result'),s.id", version)) {
            Stage stage = new Stage(row, json);
            ordered.put(stage.code, stage);
        }
        return ordered;
    }

    private List<Map<String,Object>> modelOptions(Access access, Set<Long> pinned) {
        if (!access.has("connection:use")) return Collections.emptyList();
        return models.options(pinned).stream().filter(model -> "chat".equals(model.get("modelType"))).collect(Collectors.toList());
    }

    private Set<Long> chatModels(Access access, Set<Long> pinned) {
        return modelOptions(access, pinned).stream().map(row -> id(row, "revisionId")).collect(Collectors.toSet());
    }

    private Set<Long> pinned(Map<String,Stage> stages) {
        return stages.values().stream().map(stage -> stage.model).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private Map<String,Object> detail(Map<String,Object> row, Access access) {
        decorate(row, access);
        row.put("versions", db.query(VERSION_QUERY + "WHERE v.method_id=? AND (v.state='saved' OR ?) " +
            "ORDER BY v.state='draft' DESC,v.version_no DESC", id(row, "id"), access.edits(row)));
        row.put("teams", access.owns(row) ? teams(access) : Collections.emptyList());
        return row;
    }

    private List<Map<String,Object>> teams(Access access) {
        return db.query("SELECT id,name FROM demo_team WHERE status=1 ORDER BY name,id").stream()
            .filter(row -> access.teams.contains(id(row, "id"))).collect(Collectors.toList());
    }

    private Map<String,Object> versionRow(Map<String,Object> method, long versionId, Access access) {
        Map<String,Object> row = db.one(VERSION_QUERY + "WHERE v.method_id=? AND v.id=?", id(method, "id"), versionId);
        if (row == null || ("draft".equals(row.get("state")) && !access.edits(method))) throw missing();
        return row;
    }

    private Map<String,Object> saved(Map<String,Object> method, long versionId, Access access) {
        Map<String,Object> row = versionRow(method, versionId, access);
        if (!"saved".equals(row.get("state"))) throw invalid("请选择已保存的方法版本");
        return row;
    }

    private void decorate(Map<String,Object> row, Access access) {
        row.put("canEdit", access.edits(row));
        row.put("canPublish", access.publishes(row));
        row.put("canManage", access.owns(row) && access.has("method:edit"));
        row.put("canCopy", access.has("method:create"));
        row.put("canSetDefault", row.get("archivedAt") == null && access.owns(row) && access.has("method:version:create"));
        if (!access.edits(row)) row.remove("currentDraftId");
    }

    private Map<String,Object> editable(long method, Access access, boolean lock) {
        Map<String,Object> row = base(method, access, lock);
        if (!access.edits(row)) throw denied();
        return row;
    }

    private Map<String,Object> owned(long method, Access access) {
        Map<String,Object> row = base(method, access, true);
        if (!access.owns(row)) throw denied();
        return row;
    }

    private Map<String,Object> base(long method, Access access, boolean lock) {
        if (lock && db.find(METHOD, method, true) == null) throw missing();
        Map<String,Object> row = db.one(BASE_QUERY + "WHERE m.id=?", method);
        if (row == null || !access.reads(row) || (row.get("archivedAt") != null && !access.owns(row))) throw missing();
        return row;
    }

    private Access access(String permission) {
        long actor = CurrentActor.id();
        Set<String> permissions = identity.effectivePermissions(actor).stream().map(row -> (String)row.get("code")).collect(Collectors.toSet());
        if (!permissions.contains("method:read") || !permissions.contains(permission)) throw denied();
        Set<Long> teams = db.query("SELECT m.team_id FROM demo_team_member m JOIN demo_team t ON t.id=m.team_id " +
            "WHERE m.user_id=? AND t.status=1 AND m.status=1 AND m.member_role IN ('member','admin')", actor).stream()
            .map(row -> id(row, "teamId")).collect(Collectors.toSet());
        Map<Long,Set<String>> grants = new HashMap<>();
        for (Map<String,Object> row : db.query("SELECT resource_id,principal_id,principal_type,capability FROM demo_resource_grant " +
            "WHERE resource_type='method' AND revoked_at IS NULL")) {
            Long principal = id(row, "principalId");
            if (("user".equals(row.get("principalType")) && principal.equals(actor)) ||
                ("team".equals(row.get("principalType")) && teams.contains(principal))) {
                grants.computeIfAbsent(id(row, "resourceId"), unused -> new HashSet<>()).add((String)row.get("capability"));
            }
        }
        return new Access(actor, permissions, teams, grants);
    }

    private static final class Access {
        final long user;
        final Set<String> permissions;
        final Set<Long> teams;
        final Map<Long,Set<String>> grants;

        Access(long user, Set<String> permissions, Set<Long> teams, Map<Long,Set<String>> grants) {
            this.user = user; this.permissions = permissions; this.teams = teams; this.grants = grants;
        }

        boolean has(String permission) { return permissions.contains(permission); }
        boolean owns(Map<String,Object> row) { return Objects.equals(user, id(row, "ownerUserId")); }
        Set<String> grants(Map<String,Object> row) { return grants.getOrDefault(id(row, "id"), Collections.emptySet()); }
        boolean uses(Map<String,Object> row) {
            return owns(row) || "builtin".equals(row.get("visibility")) ||
                ("team".equals(row.get("visibility")) && teams.contains(id(row, "teamId"))) || grants(row).contains("use");
        }
        boolean reads(Map<String,Object> row) {
            return uses(row) || !Collections.disjoint(grants(row), Arrays.asList("read", "edit", "publish"));
        }
        boolean edits(Map<String,Object> row) {
            return row.get("archivedAt") == null && has("method:edit") && (owns(row) || grants(row).contains("edit"));
        }
        boolean publishes(Map<String,Object> row) {
            return edits(row) && has("method:version:create") && (owns(row) || grants(row).contains("publish"));
        }
    }

    private static final class Draft {
        final long id, taxonomy;
        final Long knowledgeVersion;
        final Map<String,Object> row;
        final Map<String,Stage> stages;
        final Set<Long> availableModels;

        Draft(long id, long taxonomy, Long knowledgeVersion, Map<String,Object> row, Map<String,Stage> stages,
              Set<Long> availableModels) {
            this.id = id; this.taxonomy = taxonomy; this.knowledgeVersion = knowledgeVersion; this.row = row;
            this.stages = stages; this.availableModels = availableModels;
        }
    }
}
