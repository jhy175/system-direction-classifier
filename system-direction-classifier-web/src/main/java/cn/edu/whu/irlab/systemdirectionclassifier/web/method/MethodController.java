package cn.edu.whu.irlab.systemdirectionclassifier.web.method;

import cn.edu.whu.irlab.systemdirectionclassifier.manager.method.MethodManager;
import cn.edu.whu.irlab.systemdirectionclassifier.web.identity.IdentityResponses;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Profile("mysql")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/methods")
public class MethodController {
    private final MethodManager methods;

    @GetMapping
    public Object catalog(@RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "") String taxonomy,
                          @RequestParam(defaultValue = "") String scope, @RequestParam(defaultValue = "false") boolean archived,
                          @RequestParam(defaultValue = "1") int page) {
        return IdentityResponses.ok(methods.catalog(search, taxonomy, scope, archived, page));
    }

    @GetMapping("/options")
    public Object options() { return IdentityResponses.ok(methods.options()); }

    @GetMapping("/dependencies")
    public Object dependencies(@RequestParam long taxonomyId, @RequestParam(required = false) Long methodId) {
        return IdentityResponses.ok(methods.dependencies(taxonomyId, methodId));
    }

    @GetMapping("/{method}")
    public Object detail(@PathVariable long method) { return IdentityResponses.ok(methods.detail(method)); }

    @GetMapping("/{method}/versions/{version}")
    public Object version(@PathVariable long method, @PathVariable long version) {
        return IdentityResponses.ok(methods.version(method, version));
    }

    @PostMapping
    public Object create(@RequestBody JsonNode input) { return IdentityResponses.ok(methods.create(input)); }

    @PutMapping("/{method}")
    public Object update(@PathVariable long method, @RequestBody JsonNode input) {
        return IdentityResponses.ok(methods.update(method, input));
    }

    @PutMapping("/{method}/archive")
    public Object archive(@PathVariable long method, @RequestBody JsonNode input) {
        return IdentityResponses.ok(methods.archive(method, input));
    }

    @PostMapping("/{method}/versions/{version}/copy")
    public Object copy(@PathVariable long method, @PathVariable long version, @RequestBody JsonNode input) {
        return IdentityResponses.ok(methods.copy(method, version, input));
    }

    @PutMapping("/{method}/versions/{version}/default")
    public Object setDefault(@PathVariable long method, @PathVariable long version) {
        return IdentityResponses.ok(methods.setDefault(method, version));
    }

    @PostMapping("/{method}/draft")
    public Object createDraft(@PathVariable long method, @RequestBody JsonNode input) {
        return IdentityResponses.ok(methods.createDraft(method, input));
    }

    @PutMapping("/{method}/draft")
    public Object saveDraft(@PathVariable long method, @RequestBody JsonNode input) {
        return IdentityResponses.ok(methods.saveDraft(method, input));
    }

    @PostMapping("/{method}/draft/validate")
    public Object validate(@PathVariable long method, @RequestBody JsonNode input) {
        return IdentityResponses.ok(methods.validateDraft(method, input));
    }

    @PostMapping("/{method}/draft/publish")
    public ResponseEntity<Object> publish(@PathVariable long method, @RequestBody JsonNode input) {
        Map<String,Object> result = methods.publish(method, input);
        if (Boolean.FALSE.equals(result.get("valid"))) {
            Map<String,Object> response = IdentityResponses.ok(result);
            response.put("success", false);
            response.put("message", "请先处理方法配置问题");
            return ResponseEntity.unprocessableEntity().body(response);
        }
        return ResponseEntity.ok(IdentityResponses.ok(result));
    }
}
