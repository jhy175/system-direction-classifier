package cn.edu.whu.irlab.systemdirectionclassifier.web.model;

import cn.edu.whu.irlab.systemdirectionclassifier.manager.model.ModelConnectionManager;
import cn.edu.whu.irlab.systemdirectionclassifier.web.identity.IdentityResponses;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@Profile("mysql")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/model-connections")
public class ModelConnectionController {
    private final ModelConnectionManager manager;

    @GetMapping
    public Object catalog(@RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "") String type,
                          @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "1") int page) {
        return IdentityResponses.ok(manager.catalog(search, type, status, page));
    }

    @GetMapping("/options")
    public Object options() { return IdentityResponses.ok(manager.options()); }

    @GetMapping("/{id}")
    public Object detail(@PathVariable long id) { return IdentityResponses.ok(manager.detail(id)); }

    @PostMapping
    public Object create(@RequestBody JsonNode input) { return IdentityResponses.ok(manager.create(input)); }

    @PutMapping("/{id}")
    public Object update(@PathVariable long id, @RequestBody JsonNode input) {
        return IdentityResponses.ok(manager.update(id, input));
    }

    @PostMapping("/{id}/copy")
    public Object copy(@PathVariable long id, @RequestBody JsonNode input) {
        return IdentityResponses.ok(manager.copy(id, input));
    }

    @PutMapping("/{id}/status")
    public Object status(@PathVariable long id, @RequestBody JsonNode input) {
        return IdentityResponses.ok(manager.status(id, input));
    }

    @PostMapping("/{id}/tests")
    public Object test(@PathVariable long id, @RequestBody JsonNode input) {
        return IdentityResponses.ok(manager.test(id, input));
    }
}
