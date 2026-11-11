package cn.edu.whu.irlab.systemdirectionclassifier.web.recognition;

import cn.edu.whu.irlab.systemdirectionclassifier.manager.recognition.RecognitionManager;
import cn.edu.whu.irlab.systemdirectionclassifier.web.identity.IdentityResponses;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("mysql")
@RequestMapping("/api/debug")
@RequiredArgsConstructor
public class RecognitionController {
    private final RecognitionManager manager;

    @PostMapping("/runs")
    public Object start(@RequestBody JsonNode input) { return IdentityResponses.ok(manager.start(input)); }

    @GetMapping("/runs/{run}")
    public Object detail(@PathVariable long run) { return IdentityResponses.ok(manager.detail(run)); }

    @GetMapping("/runs/{run}/stages/{stage}")
    public Object stage(@PathVariable long run, @PathVariable String stage, @RequestParam(defaultValue = "false") boolean calls) {
        return IdentityResponses.ok(manager.stage(run, stage, calls));
    }

    @PostMapping("/runs/{run}/branch")
    public Object branch(@PathVariable long run, @RequestBody JsonNode input) { return IdentityResponses.ok(manager.branch(run, input)); }

    @GetMapping("/runs/{run}/disciplines")
    public Object disciplines(@PathVariable long run) { return IdentityResponses.ok(manager.disciplines(run)); }

    @PostMapping("/runs/{run}/save")
    public Object save(@PathVariable long run) { return IdentityResponses.ok(manager.save(run)); }

    @GetMapping("/history")
    public Object history(@RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "1") int page) {
        return IdentityResponses.ok(manager.history(search, page));
    }

    @GetMapping("/history/{record}")
    public Object historyDetail(@PathVariable long record) { return IdentityResponses.ok(manager.historyDetail(record)); }
}
