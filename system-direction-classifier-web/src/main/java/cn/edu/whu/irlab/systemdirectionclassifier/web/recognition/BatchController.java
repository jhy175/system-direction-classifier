package cn.edu.whu.irlab.systemdirectionclassifier.web.recognition;

import cn.edu.whu.irlab.systemdirectionclassifier.manager.recognition.BatchManager;
import cn.edu.whu.irlab.systemdirectionclassifier.web.identity.IdentityResponses;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("mysql")
@RequestMapping("/api/batch/tasks")
@RequiredArgsConstructor
public class BatchController {
    private final BatchManager manager;

    @GetMapping
    public Object list(@RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "") String status,
                       @RequestParam(defaultValue = "1") int page) {
        return IdentityResponses.ok(manager.list(search, status, page));
    }

    @PostMapping
    public Object create(@RequestBody JsonNode input) { return IdentityResponses.ok(manager.create(input)); }

    @PostMapping("/{task}/imports/inspect")
    public Object inspect(@PathVariable long task, @RequestBody JsonNode input) {
        return IdentityResponses.ok(manager.inspect(task, input));
    }

    @PostMapping("/{task}/imports")
    public Object check(@PathVariable long task, @RequestBody JsonNode input) {
        return IdentityResponses.ok(manager.check(task, input));
    }

    @PostMapping("/{task}/submit")
    public ResponseEntity<Object> submit(@PathVariable long task, @RequestBody JsonNode input) {
        return ResponseEntity.accepted().body(IdentityResponses.ok(manager.submit(task, input)));
    }

    @GetMapping("/{task}")
    public Object detail(@PathVariable long task, @RequestParam(defaultValue = "") String status,
                         @RequestParam(defaultValue = "") String discipline, @RequestParam(defaultValue = "1") int page) {
        return IdentityResponses.ok(manager.detail(task, status, discipline, page));
    }

    @GetMapping("/{task}/items/{item}")
    public Object item(@PathVariable long task, @PathVariable long item, @RequestParam(required = false) Long run) {
        return IdentityResponses.ok(manager.item(task, item, run));
    }

    @GetMapping("/{task}/items/{item}/runs/{run}/stages/{stage}")
    public Object stage(@PathVariable long task, @PathVariable long item, @PathVariable long run, @PathVariable String stage,
                        @RequestParam(defaultValue = "false") boolean calls) {
        return IdentityResponses.ok(manager.stage(task, item, run, stage, calls));
    }

    @PostMapping("/{task}/retry")
    public ResponseEntity<Object> retry(@PathVariable long task) {
        return ResponseEntity.accepted().body(IdentityResponses.ok(manager.retry(task)));
    }

    @GetMapping("/{task}/export")
    public ResponseEntity<byte[]> export(@PathVariable long task, @RequestParam String kind, @RequestParam(required = false) Long importId) {
        byte[] csv = manager.export(task, kind, importId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=batch-" + task + "-" + kind + ".csv")
            .body(csv);
    }
}
