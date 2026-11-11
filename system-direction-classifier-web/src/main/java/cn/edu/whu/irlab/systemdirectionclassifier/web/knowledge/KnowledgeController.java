package cn.edu.whu.irlab.systemdirectionclassifier.web.knowledge;

import cn.edu.whu.irlab.systemdirectionclassifier.manager.knowledge.KnowledgeFiles;
import cn.edu.whu.irlab.systemdirectionclassifier.manager.knowledge.KnowledgeManager;
import cn.edu.whu.irlab.systemdirectionclassifier.web.identity.IdentityResponses;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

@Profile("mysql")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeManager manager;

    @GetMapping
    public Object catalog(@RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "") String taxonomy,
                          @RequestParam(defaultValue = "") String scope, @RequestParam(defaultValue = "false") boolean archived,
                          @RequestParam(defaultValue = "1") int page) {
        return IdentityResponses.ok(manager.catalog(search, taxonomy, scope, archived, page));
    }

    @PostMapping
    public Object create(@RequestBody JsonNode input) { return IdentityResponses.ok(manager.create(input)); }

    @GetMapping("/{base}")
    public Object detail(@PathVariable long base) { return IdentityResponses.ok(manager.detail(base)); }

    @PutMapping("/{base}")
    public Object update(@PathVariable long base, @RequestBody JsonNode input) { return IdentityResponses.ok(manager.update(base, input)); }

    @PutMapping("/{base}/archive")
    public Object archive(@PathVariable long base, @RequestBody JsonNode input) { return IdentityResponses.ok(manager.archive(base, input)); }

    @GetMapping("/{base}/versions/{version}")
    public Object version(@PathVariable long base, @PathVariable long version) { return IdentityResponses.ok(manager.version(base, version)); }

    @GetMapping("/{base}/versions/{version}/portraits/{portrait}")
    public Object portrait(@PathVariable long base, @PathVariable long version, @PathVariable long portrait) {
        return IdentityResponses.ok(manager.portrait(base, version, portrait));
    }

    @GetMapping("/{base}/versions/{version}/export")
    public Object export(@PathVariable long base, @PathVariable long version) {
        return IdentityResponses.ok(manager.export(base, version));
    }

    @GetMapping("/{base}/versions/{version}/assets/{asset}")
    public ResponseEntity<byte[]> download(@PathVariable long base, @PathVariable long version, @PathVariable long asset) {
        KnowledgeFiles.Download download = manager.download(base, version, asset);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(download.name, StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff").body(download.bytes);
    }

    @PostMapping("/{base}/versions/{version}/copy")
    public Object copy(@PathVariable long base, @PathVariable long version, @RequestBody JsonNode input) {
        return IdentityResponses.ok(manager.copy(base, version, input));
    }

    @PutMapping("/{base}/versions/{version}/default")
    public Object makeDefault(@PathVariable long base, @PathVariable long version) {
        return IdentityResponses.ok(manager.setDefault(base, version));
    }

    @PostMapping("/{base}/draft")
    public Object createDraft(@PathVariable long base, @RequestBody JsonNode input) {
        return IdentityResponses.ok(manager.createDraft(base, input));
    }

    @PostMapping("/{base}/draft/publish")
    public Object publish(@PathVariable long base, @RequestBody JsonNode input) {
        return IdentityResponses.ok(manager.publish(base, input));
    }

    @PutMapping("/{base}/draft/resources/{kind}")
    public Object save(@PathVariable long base, @PathVariable String kind, @RequestBody JsonNode input) {
        return IdentityResponses.ok(manager.saveResource(base, kind, input));
    }

    @DeleteMapping("/{base}/draft/resources/{kind}/{resource}")
    public Object remove(@PathVariable long base, @PathVariable String kind, @PathVariable long resource,
                         @RequestBody JsonNode input) {
        return IdentityResponses.ok(manager.removeResource(base, kind, resource, input));
    }

    @PostMapping("/{base}/draft/import")
    public Object importResources(@PathVariable long base, @RequestBody JsonNode input) {
        return IdentityResponses.ok(manager.importResources(base, input));
    }

    @PostMapping("/{base}/draft/assets")
    public Object attach(@PathVariable long base, @RequestBody JsonNode input) {
        return IdentityResponses.ok(manager.attach(base, input));
    }

    @PostMapping("/{base}/draft/generate/{kind}")
    public Object generate(@PathVariable long base, @PathVariable String kind, @RequestBody JsonNode input) {
        return IdentityResponses.ok(manager.generate(base, kind, input));
    }
}
