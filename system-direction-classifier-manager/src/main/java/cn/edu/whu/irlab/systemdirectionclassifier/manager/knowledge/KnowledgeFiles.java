package cn.edu.whu.irlab.systemdirectionclassifier.manager.knowledge;

import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityException;
import cn.edu.whu.irlab.systemdirectionclassifier.dao.IdentityDAO;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static cn.edu.whu.irlab.systemdirectionclassifier.manager.knowledge.KnowledgeResources.*;

@Component
@Profile("mysql")
@RequiredArgsConstructor
public class KnowledgeFiles {
    private final IdentityDAO db;
    @Value("${classifier.knowledge.asset-directory:.local/knowledge-assets}")
    private String directory;

    Upload decode(JsonNode input, int limit) {
        String name = text(input, "fileName", 255, true);
        if (name.equals(".") || name.equals("..") || name.indexOf('"') >= 0 ||
            name.matches(".*[\\\\/:*?<>|\\p{Cntrl}].*"))
            throw invalid("无效文件名");
        String encoded = text(input, "content", (limit + 2) / 3 * 4, true);
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException error) { throw invalid("文件内容格式错误"); }
        if (bytes.length == 0 || bytes.length > limit) throw invalid("文件为空或超过大小限制");
        return new Upload(name, bytes);
    }

    long store(Upload upload, long actor) {
        String key = key(upload.hash);
        try {
            Path path = path(key, true);
            if (!Files.exists(path)) {
                Path pending = Files.createTempFile(path.getParent(), ".knowledge-", ".tmp");
                try {
                    Files.write(pending, upload.bytes);
                    try { Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE); }
                    catch (FileAlreadyExistsException ignored) { /* Same content may have been stored concurrently. */ }
                } finally { Files.deleteIfExists(pending); }
            }
            if (!Arrays.equals(Files.readAllBytes(path), upload.bytes)) throw new IOException("Unexpected file contents");
        } catch (IOException error) {
            throw new IdentityException(500, "无法保存文件，请检查文件存储目录");
        }
        db.execute("INSERT INTO demo_file_asset(original_name,storage_key,media_type,byte_size,content_sha256,status,uploaded_by) " +
            "VALUES (?,?,?,?,?,'ready',?) ON DUPLICATE KEY UPDATE id=id", upload.name, key,
            upload.name.toLowerCase(Locale.ROOT).endsWith(".json") ? "application/json" : "application/octet-stream",
            upload.bytes.length, upload.hash, actor);
        Map<String,Object> row = db.one("SELECT id,status,byte_size,content_sha256 FROM demo_file_asset WHERE storage_key=? FOR SHARE", key);
        if (!"ready".equals(row.get("status")) || !upload.hash.equals(row.get("contentSha256")) ||
            id(row, "byteSize") != upload.bytes.length) throw conflict("文件资产状态不一致");
        return id(row, "id");
    }

    void reference(long asset, String type, long object, String role) {
        db.execute("INSERT INTO demo_asset_reference(asset_id,object_type,object_id,role) VALUES (?,?,?,?) " +
            "ON DUPLICATE KEY UPDATE id=id", asset, type, object, role);
    }

    void link(long version, long asset, String role, String name, String description) {
        db.insert("demo_knowledge_asset", fields("knowledge_version_id", version, "asset_id", asset, "role", role,
            "logical_name", name, "description", description, "sort_order",
            db.count("SELECT COALESCE(MAX(sort_order),0)+1 FROM demo_knowledge_asset WHERE knowledge_version_id=?", version)));
        reference(asset, "knowledge_version", version, role);
    }

    void remove(long version, long attachment) {
        Map<String,Object> found = db.one("SELECT asset_id FROM demo_knowledge_asset WHERE id=? AND knowledge_version_id=? AND role='similarity'",
            attachment, version);
        if (found == null) throw missing();
        db.execute("DELETE FROM demo_knowledge_asset WHERE id=?", attachment);
        db.execute("DELETE FROM demo_asset_reference WHERE object_type='knowledge_version' AND object_id=? " +
            "AND asset_id=? AND role='similarity' AND NOT EXISTS (SELECT id FROM demo_knowledge_asset " +
            "WHERE knowledge_version_id=? AND asset_id=? AND role='similarity')",
            version, found.get("assetId"), version, found.get("assetId"));
    }

    List<Map<String,Object>> list(long version) {
        return db.query("SELECT k.id,SUBSTRING_INDEX(k.logical_name,'/',-1) AS name,k.description," +
            "a.byte_size,a.content_sha256,a.created_at FROM demo_knowledge_asset k JOIN demo_file_asset a ON a.id=k.asset_id " +
            "WHERE k.knowledge_version_id=? AND k.role='similarity' ORDER BY k.sort_order,k.id", version);
    }

    List<Map<String,Object>> manifest(long version) {
        return db.query("SELECT k.role,k.logical_name,k.description,a.content_sha256,a.byte_size " +
            "FROM demo_knowledge_asset k JOIN demo_file_asset a ON a.id=k.asset_id " +
            "WHERE k.knowledge_version_id=? ORDER BY k.sort_order,k.id", version);
    }

    void copy(long source, long target) {
        db.execute("INSERT INTO demo_knowledge_asset (knowledge_version_id,asset_id,role,logical_name,description," +
            "applicable_scope_json,metadata_json,sort_order) SELECT ?,asset_id,role,logical_name,description," +
            "applicable_scope_json,metadata_json,sort_order FROM demo_knowledge_asset WHERE knowledge_version_id=?", target, source);
        for (Map<String,Object> asset : db.query("SELECT DISTINCT asset_id,role FROM demo_knowledge_asset WHERE knowledge_version_id=?", target))
            reference(id(asset, "assetId"), "knowledge_version", target, (String)asset.get("role"));
        db.execute("INSERT INTO demo_knowledge_index (knowledge_version_id,index_type,source_manifest_hash,model_revision_id," +
            "vector_space_signature,dimension,entry_count,build_config_json,knowledge_asset_id,status) " +
            "SELECT ?,i.index_type,i.source_manifest_hash,i.model_revision_id,i.vector_space_signature,i.dimension," +
            "i.entry_count,i.build_config_json,a2.id,i.status FROM demo_knowledge_index i " +
            "LEFT JOIN demo_knowledge_asset a1 ON a1.id=i.knowledge_asset_id " +
            "LEFT JOIN demo_knowledge_asset a2 ON a2.knowledge_version_id=? AND a2.logical_name=a1.logical_name " +
            "WHERE i.knowledge_version_id=?", target, target, source);
    }

    Download download(long version, long attachment) {
        Map<String,Object> row = db.one("SELECT SUBSTRING_INDEX(k.logical_name,'/',-1) AS name,a.storage_key,a.content_sha256 " +
            "FROM demo_knowledge_asset k JOIN demo_file_asset a ON a.id=k.asset_id " +
            "WHERE k.knowledge_version_id=? AND k.id=? AND a.status='ready' AND k.role='similarity'", version, attachment);
        if (row == null) throw missing();
        try {
            String hash = (String)row.get("contentSha256");
            String key = key(hash);
            if (!key.equals(row.get("storageKey"))) throw new IOException("Invalid storage key");
            byte[] bytes = Files.readAllBytes(path(key, false));
            if (!hash.equals(digest(bytes))) throw new IOException("Invalid file checksum");
            return new Download((String)row.get("name"), bytes);
        } catch (IOException error) {
            throw new IdentityException(409, "附件文件缺失或校验失败");
        }
    }

    private static String key(String hash) {
        return "sha256/" + hash.substring(0, 2) + "/" + hash;
    }

    private Path path(String key, boolean create) throws IOException {
        if (!key.matches("sha256/[0-9a-f]{2}/[0-9a-f]{64}")) throw new IOException("Invalid storage key");
        Path configured = Paths.get(directory).toAbsolutePath().normalize();
        if (create) Files.createDirectories(configured);
        Path root = configured.toRealPath();
        Path file = root.resolve(key);
        Path sha = file.getParent().getParent();
        if (create && !Files.exists(sha)) Files.createDirectories(sha);
        if (!sha.toRealPath().startsWith(root)) throw new IOException("Invalid asset directory");
        if (create && !Files.exists(file.getParent())) Files.createDirectories(file.getParent());
        if (!file.getParent().toRealPath().startsWith(root) ||
            (Files.exists(file) && !file.toRealPath().startsWith(root))) throw new IOException("Invalid asset location");
        return file;
    }

    static final class Upload {
        final String name;
        final byte[] bytes;
        final String hash;

        Upload(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
            this.hash = digest(bytes);
        }
    }

    public static final class Download {
        public final String name;
        public final byte[] bytes;

        Download(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }
}
