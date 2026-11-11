package cn.edu.whu.irlab.systemdirectionclassifier.manager.knowledge;

import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.CurrentActor;
import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityException;
import cn.edu.whu.irlab.systemdirectionclassifier.dao.IdentityDAO;
import cn.edu.whu.irlab.systemdirectionclassifier.manager.identity.IdentityManager;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static cn.edu.whu.irlab.systemdirectionclassifier.manager.knowledge.KnowledgeResources.*;

@Component
@Profile("mysql")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KnowledgeManager {
    private static final String BASE_QUERY =
        "SELECT b.*,t.code AS taxonomy_code,t.name AS taxonomy_name,u.login AS owner_login," +
        "u.display_name AS owner_name, tm.name AS team_name, latest.version_no AS latest_number," +
        " def.version_no AS default_number, " +
        "(SELECT COUNT(*) FROM demo_knowledge_discipline d WHERE d.knowledge_version_id=" +
        "COALESCE(b.latest_version_id,b.current_draft_id)) AS profile_count " +
        "FROM demo_knowledge_base b JOIN demo_taxonomy t ON t.id=b.taxonomy_id " +
        "LEFT JOIN demo_user u ON u.id=b.owner_user_id LEFT JOIN demo_team tm ON tm.id=b.team_id " +
        "LEFT JOIN demo_knowledge_version latest ON latest.id=b.latest_version_id " +
        "LEFT JOIN demo_knowledge_version def ON def.id=b.default_version_id ";
    private static final String VERSION_QUERY =
        "SELECT v.id,v.knowledge_base_id,v.taxonomy_id,v.version_no,v.state,v.revision,v.based_on_version_id," +
        "v.change_note,v.saved_at,v.manifest_hash,u.login AS saved_by_login," +
        "(SELECT COUNT(*) FROM demo_knowledge_discipline d WHERE d.knowledge_version_id=v.id) AS profile_count," +
        "(SELECT COUNT(*) FROM demo_knowledge_summary s WHERE s.knowledge_version_id=v.id) AS summary_count," +
        "(SELECT COUNT(*) FROM demo_knowledge_cluster c WHERE c.knowledge_version_id=v.id) AS cluster_count " +
        "FROM demo_knowledge_version v LEFT JOIN demo_user u ON u.id=v.saved_by ";

    private final IdentityDAO db;
    private final IdentityManager identity;
    private final KnowledgeResources resources;
    private final KnowledgeFiles files;

    public Map<String,Object> catalog(String search, String taxonomy, String scope, boolean archived, int page) {
        Access access = access("knowledge:read");
        if (page < 1 || page > 100000) throw invalid("无效页码");
        String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<Map<String,Object>> visible = new ArrayList<>();
        for (Map<String,Object> base : db.query(BASE_QUERY + " ORDER BY b.updated_at DESC,b.id DESC")) {
            if (!access.canRead(base) || (!access.owner(base) && base.get("archivedAt") != null)) continue;
            if (archived != (base.get("archivedAt") != null)) continue;
            if (taxonomy != null && !taxonomy.isEmpty() && !taxonomy.equals(base.get("taxonomyCode"))) continue;
            if ("mine".equals(scope) && !access.owner(base)) continue;
            if ("team".equals(scope) && !"team".equals(base.get("visibility"))) continue;
            if ("builtin".equals(scope) && !"builtin".equals(base.get("visibility"))) continue;
            if (!needle.isEmpty() && !(
                Objects.toString(base.get("name"), "") + " " + Objects.toString(base.get("description"), "")
            ).toLowerCase(Locale.ROOT).contains(needle)) continue;
            decorate(base, access);
            visible.add(base);
        }
        int start = Math.min(visible.size(), (page - 1) * 20);
        return fields("items", visible.subList(start, Math.min(visible.size(), start + 20)),
            "total", visible.size(), "page", page, "pageSize", 20,
            "canCreate", access.has("knowledge:edit"),
            "taxonomies", db.query("SELECT id,code,name FROM demo_taxonomy ORDER BY id"),
            "teams", db.query("SELECT id,name FROM demo_team WHERE status=1 ORDER BY name,id").stream()
                .filter(team -> access.teamIds.contains(id(team, "id"))).collect(Collectors.toList()));
    }

    public Map<String,Object> detail(long baseId) {
        Access access = access("knowledge:read");
        return detail(base(baseId, access, false), access);
    }

    private Map<String,Object> detail(Map<String,Object> base, Access access) {
        decorate(base, access);
        base.put("versions", db.query(VERSION_QUERY +
            "WHERE v.knowledge_base_id=? AND (v.state='saved' OR ?) " +
            "ORDER BY v.state='draft' DESC,v.version_no DESC", id(base, "id"), access.canEdit(base)));
        return base;
    }

    public Map<String,Object> version(long baseId, long versionId) {
        Access access = access("knowledge:read");
        Map<String,Object> base = base(baseId, access, false);
        Map<String,Object> version = version(base, versionId, access);
        return fields("version", version, "resources", resources.snapshot(versionId, false),
            "validation", resources.validate(versionId), "assets", files.list(versionId));
    }

    public Object portrait(long baseId, long versionId, long profileId) {
        Access access = access("knowledge:read");
        version(base(baseId, access, false), versionId, access);
        return resources.portrait(versionId, profileId);
    }

    public Object export(long baseId, long versionId) {
        Access access = access("knowledge:read");
        Map<String,Object> base = base(baseId, access, false);
        version(base, versionId, access);
        return fields("format", "discipline-knowledge-1", "taxonomy", base.get("taxonomyCode"),
            "name", base.get("name"), "resources", resources.snapshot(versionId, true));
    }

    @Transactional
    public Object create(JsonNode input) {
        Access access = access("knowledge:edit");
        String taxonomy = text(input, "taxonomy", 64, true);
        Map<String,Object> type = db.one("SELECT id FROM demo_taxonomy WHERE code=?", taxonomy);
        if (type == null) throw invalid("请选择已有学科体系");
        long base = insertBase(id(type, "id"), access.userId, text(input, "name", 255, true),
            text(input, "description", 1000, false), null);
        draft(base, id(type, "id"), null, null);
        return detail(base(base, access, false), access);
    }

    @Transactional
    public Object copy(long baseId, long fromId, JsonNode input) {
        Access access = access("knowledge:edit");
        Map<String,Object> original = base(baseId, access, false);
        savedVersion(original, fromId, access);
        long taxonomyId = id(original, "taxonomyId");
        long base = insertBase(taxonomyId, access.userId, text(input, "name", 255, true),
            (String)original.get("description"), fromId);
        draft(base, taxonomyId, null, fromId);
        return detail(base(base, access, false), access);
    }

    @Transactional
    public Object createDraft(long baseId, JsonNode input) {
        Access access = access("knowledge:edit");
        Map<String,Object> base = editable(baseId, access);
        if (base.get("currentDraftId") != null) throw conflict("已有草稿，请继续编辑现有草稿");
        long source = number(input, "versionId");
        savedVersion(base, source, access);
        draft(baseId, id(base, "taxonomyId"), source, source);
        return detail(base(baseId, access, false), access);
    }

    private long insertBase(long taxonomyId, long owner, String name, String description, Long source) {
        return db.insert("demo_knowledge_base", fields("taxonomy_id", taxonomyId, "owner_user_id", owner,
            "name", name, "description", description, "copied_from_version_id", source));
    }

    private void draft(long baseId, long taxonomyId, Long parent, Long source) {
        long draftId = db.insert("demo_knowledge_version", fields("knowledge_base_id", baseId,
            "taxonomy_id", taxonomyId, "based_on_version_id", parent, "schema_version", "discipline-knowledge-1"));
        if (source != null) {
            resources.copy(source, draftId);
            files.copy(source, draftId);
        }
        db.execute("UPDATE demo_knowledge_base SET current_draft_id=? WHERE id=?", draftId, baseId);
    }

    @Transactional
    public Object update(long baseId, JsonNode input) {
        Access access = access("knowledge:edit");
        Map<String,Object> base = owned(baseId, access);
        String name = text(input, "name", 255, true);
        String description = text(input, "description", 1000, false);
        String visibility = text(input, "visibility", 16, true);
        Long team = null;
        if ("builtin".equals(base.get("visibility"))) {
            if (!"builtin".equals(visibility)) throw invalid("内置知识库的共享范围固定");
        } else if ("team".equals(visibility)) {
            team = number(input, "teamId");
            if (!access.teamIds.contains(team)) throw denied();
        } else if (!"private".equals(visibility)) {
            throw invalid("请选择个人或团队共享");
        }
        db.execute("UPDATE demo_knowledge_base SET name=?,description=?,visibility=?,team_id=? WHERE id=?",
            name, description, visibility, team, baseId);
        return detail(base(baseId, access, false), access);
    }

    @Transactional
    public Object archive(long baseId, JsonNode input) {
        Access access = access("knowledge:edit");
        owned(baseId, access);
        if (!input.path("archived").isBoolean()) throw invalid("请提供归档状态");
        db.execute("UPDATE demo_knowledge_base SET archived_at=" +
            (input.get("archived").asBoolean() ? "UTC_TIMESTAMP()" : "NULL") + " WHERE id=?", baseId);
        return detail(base(baseId, access, false), access);
    }

    @Transactional
    public Object setDefault(long baseId, long versionId) {
        Access access = access("knowledge:version:create");
        Map<String,Object> base = owned(baseId, access);
        if (base.get("archivedAt") != null) throw conflict("请先恢复已归档知识库");
        savedVersion(base, versionId, access);
        db.execute("UPDATE demo_knowledge_base SET default_version_id=? WHERE id=?", versionId, baseId);
        return detail(base(baseId, access, false), access);
    }

    @Transactional
    public Object saveResource(long baseId, String kind, JsonNode input) {
        Map<String,Object> base = editable(baseId, access("knowledge:edit"));
        long draft = checkDraft(base, input);
        JsonNode item = input.get("item");
        if (item == null || !item.isObject()) throw invalid("资源必须为 JSON 对象");
        long resourceId = resources.save(kind, draft, id(base, "taxonomyId"), item, null);
        return fields("id", resourceId, "revision", advance(baseId, draft));
    }

    @Transactional
    public Object removeResource(long baseId, String kind, long resourceId, JsonNode input) {
        Map<String,Object> base = editable(baseId, access("knowledge:edit"));
        long draft = checkDraft(base, input);
        if ("assets".equals(kind)) files.remove(draft, resourceId);
        else resources.remove(kind, draft, resourceId);
        return fields("revision", advance(baseId, draft));
    }

    @Transactional
    public Object publish(long baseId, JsonNode input) {
        Access access = access("knowledge:version:create");
        lock(baseId);
        Map<String,Object> base = base(baseId, access, false);
        if (!access.canPublish(base)) throw denied();
        long draft = checkDraft(base, input);
        String note = text(input, "changeNote", 1000, true);
        Map<String,Object> validation = resources.validate(draft);
        if (id(validation, "profiles") == 0) throw invalid("请至少添加一条学科画像后再保存版本");
        if (!Boolean.TRUE.equals(validation.get("compatible")) && !input.path("allowIncomplete").asBoolean()) {
            throw conflict("资源暂不满足当前识别算法的运行条件。请先检查资源，或确认保存为不可运行的版本");
        }
        Map<String,Object> manifest = fields("format", "discipline-knowledge-1",
            "taxonomy", base.get("taxonomyCode"), "resources", resources.manifest(draft), "files", files.manifest(draft));
        long next = db.count("SELECT COALESCE(MAX(version_no),0)+1 FROM demo_knowledge_version WHERE knowledge_base_id=?", baseId);
        db.execute("UPDATE demo_knowledge_version SET state='saved',version_no=?,saved_at=UTC_TIMESTAMP()," +
            "saved_by=?,change_note=?,manifest_json=?,manifest_hash=?,validation_report_json=? WHERE id=?",
            next, access.userId, note, resources.json(manifest), resources.hash(manifest), resources.json(validation), draft);
        db.execute("UPDATE demo_knowledge_base SET current_draft_id=NULL,latest_version_id=?," +
            "default_version_id=COALESCE(default_version_id,?) WHERE id=?", draft, draft, baseId);
        return fields("versionId", draft, "versionNo", next);
    }

    @Transactional
    public Object importResources(long baseId, JsonNode input) {
        Access access = access("knowledge:edit");
        Map<String,Object> base = editable(baseId, access);
        long draft = checkDraft(base, input);
        KnowledgeFiles.Upload upload = files.decode(input, 32 * 1024 * 1024);
        JsonNode snapshot = resources.parse(upload.bytes);
        if (!snapshot.isObject() || !Objects.equals(base.get("taxonomyCode"), snapshot.path("taxonomy").asText())) {
            throw invalid("导入文件的学科体系必须与当前知识库一致");
        }
        Map<String,Object> preview = resources.inspectImport(draft, snapshot.path("resources"));
        preview.put("name", upload.name);
        preview.put("sha256", upload.hash);
        if (!input.path("apply").asBoolean()) return preview;
        if (!upload.hash.equals(input.path("previewHash").asText())) throw conflict("文件已变化，请重新预览");
        if (!Boolean.TRUE.equals(preview.get("valid"))) throw invalid("请先修正导入预览中的问题");
        long asset = files.store(upload, access.userId);
        files.link(draft, asset, "import_source", "imports/" + upload.hash + "/" + upload.name, null);
        long importId = db.insert("demo_knowledge_import", fields("target_version_id", draft,
            "target_revision", number(input, "revision"), "source_asset_id", asset, "resource_type", "snapshot",
            "parser_version", "discipline-knowledge-1", "status", "applied", "counts_json", resources.json(preview.get("counts"))));
        db.execute("UPDATE demo_knowledge_import SET applied_at=UTC_TIMESTAMP(),created_by=? WHERE id=?", access.userId, importId);
        files.reference(asset, "knowledge_import", importId, "import_source");
        resources.applyImport(draft, id(base, "taxonomyId"), snapshot.path("resources"), importId, asset);
        return fields("revision", advance(baseId, draft), "counts", preview.get("counts"));
    }

    @Transactional
    public Object attach(long baseId, JsonNode input) {
        Access access = access("knowledge:edit");
        Map<String,Object> base = editable(baseId, access);
        long draft = checkDraft(base, input);
        KnowledgeFiles.Upload upload = files.decode(input, 8 * 1024 * 1024);
        if (!upload.name.toLowerCase(Locale.ROOT).matches(".+\\.(csv|tsv|json)")) throw invalid("请上传 CSV、TSV 或 JSON 文件");
        Long replace = optionalNumber(input, "replaceId");
        if (replace != null) files.remove(draft, replace);
        long asset = files.store(upload, access.userId);
        files.link(draft, asset, "similarity", "similarities/" + upload.name,
            text(input, "description", 1000, false));
        return fields("revision", advance(baseId, draft));
    }

    public KnowledgeFiles.Download download(long baseId, long versionId, long attachmentId) {
        Access access = access("knowledge:read");
        version(base(baseId, access, false), versionId, access);
        return files.download(versionId, attachmentId);
    }

    @Transactional
    public Object generate(long baseId, String kind, JsonNode input) {
        Map<String,Object> base = editable(baseId, access("knowledge:edit"));
        long draft = checkDraft(base, input);
        List<Map<String,Object>> rows = resources.generate(kind, draft);
        if (!input.path("apply").asBoolean()) return fields("items", rows);
        resources.applyGenerated(kind, draft, id(base, "taxonomyId"), rows);
        return fields("revision", advance(baseId, draft));
    }

    private long advance(long baseId, long draft) {
        db.execute("UPDATE demo_knowledge_version SET revision=revision+1 WHERE id=?", draft);
        db.execute("UPDATE demo_knowledge_base SET updated_at=UTC_TIMESTAMP() WHERE id=?", baseId);
        return db.count("SELECT revision FROM demo_knowledge_version WHERE id=?", draft);
    }

    private long checkDraft(Map<String,Object> base, JsonNode input) {
        Long draft = id(base, "currentDraftId");
        if (base.get("archivedAt") != null || draft == null || !draft.equals(number(input, "draftId"))) {
            throw conflict("知识库草稿已变化，请重新打开");
        }
        Map<String,Object> stored = db.one("SELECT state,revision FROM demo_knowledge_version WHERE id=? FOR UPDATE", draft);
        if (stored == null || !"draft".equals(stored.get("state")) || !id(stored, "revision").equals(number(input, "revision"))) {
            throw conflict("草稿已被更新，请刷新页面后重试");
        }
        return draft;
    }

    private Map<String,Object> savedVersion(Map<String,Object> base, long versionId, Access access) {
        Map<String,Object> version = version(base, versionId, access);
        if (!"saved".equals(version.get("state"))) throw invalid("请选择一个已保存的版本");
        return version;
    }

    private Map<String,Object> version(Map<String,Object> base, long versionId, Access access) {
        Map<String,Object> row = db.one(VERSION_QUERY + "WHERE v.knowledge_base_id=? AND v.id=?", id(base, "id"), versionId);
        if (row == null || ("draft".equals(row.get("state")) && !access.canEdit(base))) throw missing();
        return row;
    }

    private void lock(long baseId) {
        if (db.one("SELECT id FROM demo_knowledge_base WHERE id=? FOR UPDATE", baseId) == null) throw missing();
    }

    private Map<String,Object> owned(long baseId, Access access) {
        Map<String,Object> row = base(baseId, access, true);
        if (!access.owner(row)) throw denied();
        return row;
    }

    private Map<String,Object> editable(long baseId, Access access) {
        Map<String,Object> row = base(baseId, access, true);
        if (!access.canEdit(row)) throw denied();
        return row;
    }

    private Map<String,Object> base(long baseId, Access access, boolean lock) {
        if (lock) lock(baseId);
        Map<String,Object> row = db.one(BASE_QUERY + "WHERE b.id=?", baseId);
        if (row == null || !access.canRead(row) || (row.get("archivedAt") != null && !access.owner(row))) throw missing();
        return row;
    }

    private void decorate(Map<String,Object> row, Access access) {
        row.put("canEdit", access.canEdit(row));
        row.put("canPublish", access.canPublish(row));
        row.put("canManage", access.owner(row) && access.has("knowledge:edit"));
        if (!access.canEdit(row)) row.remove("currentDraftId");
    }

    private Access access(String permission) {
        long userId = CurrentActor.id();
        Set<String> permissions = identity.effectivePermissions(userId).stream()
            .map(p -> (String)p.get("code")).collect(Collectors.toSet());
        if (!permissions.contains("knowledge:read") || !permissions.contains(permission)) throw denied();
        Set<Long> teams = db.query("SELECT m.team_id FROM demo_team_member m JOIN demo_team t ON t.id=m.team_id " +
            "WHERE m.user_id=? AND t.status=1 AND m.status=1 AND m.member_role IN ('member','admin')", userId).stream()
            .map(row -> id(row, "teamId")).collect(Collectors.toSet());
        Map<Long,Set<String>> grants = new HashMap<>();
        for (Map<String,Object> grant : db.query("SELECT resource_id,principal_id,principal_type,capability " +
            "FROM demo_resource_grant WHERE resource_type='knowledge_base' AND revoked_at IS NULL")) {
            Long principal = id(grant, "principalId");
            if (("user".equals(grant.get("principalType")) && principal.equals(userId)) ||
                ("team".equals(grant.get("principalType")) && teams.contains(principal))) {
                grants.computeIfAbsent(id(grant, "resourceId"), ignored -> new HashSet<>()).add((String)grant.get("capability"));
            }
        }
        return new Access(userId, permissions, teams, grants);
    }

    private static final class Access {
        final Long userId;
        final Set<String> permissions;
        final Set<Long> teamIds;
        final Map<Long,Set<String>> grants;

        Access(long userId, Set<String> permissions, Set<Long> teamIds, Map<Long,Set<String>> grants) {
            this.userId = userId;
            this.permissions = permissions;
            this.teamIds = teamIds;
            this.grants = grants;
        }

        boolean has(String permission) { return permissions.contains(permission); }
        boolean owner(Map<String,Object> base) { return userId.equals(id(base, "ownerUserId")); }
        Set<String> grants(Map<String,Object> base) {
            return grants.getOrDefault(id(base, "id"), Collections.emptySet());
        }
        boolean canRead(Map<String,Object> base) {
            return owner(base) || "builtin".equals(base.get("visibility")) ||
                ("team".equals(base.get("visibility")) && teamIds.contains(id(base, "teamId"))) ||
                !Collections.disjoint(grants(base), Arrays.asList("read", "use", "edit", "publish"));
        }
        boolean canEdit(Map<String,Object> base) {
            return base.get("archivedAt") == null && has("knowledge:edit") &&
                (owner(base) || grants(base).contains("edit"));
        }
        boolean canPublish(Map<String,Object> base) {
            return canEdit(base) && has("knowledge:version:create") &&
                (owner(base) || grants(base).contains("publish"));
        }
    }
}
