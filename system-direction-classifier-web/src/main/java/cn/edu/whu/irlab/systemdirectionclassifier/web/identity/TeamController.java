package cn.edu.whu.irlab.systemdirectionclassifier.web.identity;
import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/teams/{teamId}")
public class TeamController {
    private final IdentityService identity;
    @GetMapping("/members") public Object members(@PathVariable Long teamId) {
        return IdentityResponses.ok(identity.members(teamId));
    }
    @PutMapping("/members/{userId}") public Object save(@PathVariable Long teamId,@PathVariable Long userId,@RequestBody JsonNode input) {
        return IdentityResponses.ok(identity.saveMember(teamId,userId,input));
    }
    @DeleteMapping("/members/{userId}") public Object remove(@PathVariable Long teamId,@PathVariable Long userId) {
        identity.removeMember(teamId,userId);return IdentityResponses.ok(null);
    }
    @PutMapping("/owner") public Object transfer(@PathVariable Long teamId,@RequestBody JsonNode input) {
        if(input==null || !input.isObject() || input.size()!=1 || !input.has("userId") || !input.get("userId").isIntegralNumber() || !input.get("userId").canConvertToLong())
            throw new IdentityException(400,"请提供新负责人的userId");
        identity.transferTeam(teamId,input.get("userId").asLong());return IdentityResponses.ok(null);
    }
}

