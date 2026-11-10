package cn.edu.whu.irlab.systemdirectionclassifier.web.identity;
import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/{resource:users|roles|permissions|menus|teams}")
public class IdentityController {
    private final IdentityService identity;
    @GetMapping public Object list(@PathVariable String resource,@RequestParam Map<String,String> query) {
        return IdentityResponses.ok(identity.list(resource,query));
    }
    @GetMapping("/{id}") public Object get(@PathVariable String resource,@PathVariable Long id) {
        return IdentityResponses.ok(identity.get(resource,id));
    }
    @PostMapping public Object create(@PathVariable String resource,@RequestBody JsonNode input) {
        return IdentityResponses.ok(identity.create(resource,input));
    }
    @RequestMapping(value="/{id}",method={RequestMethod.PUT,RequestMethod.PATCH})
    public Object update(@PathVariable String resource,@PathVariable Long id,@RequestBody JsonNode input) {
        return IdentityResponses.ok(identity.update(resource,id,input));
    }
    @DeleteMapping("/{id}") public Object remove(@PathVariable String resource,@PathVariable Long id) {
        identity.remove(resource,id);return IdentityResponses.ok(null);
    }
    @GetMapping("/tree") public Object tree(@PathVariable String resource) {
        return IdentityResponses.ok(identity.tree(resource));
    }
    @GetMapping("/{id}/{relation:roles|permissions|menus}")
    public Object relations(@PathVariable String resource,@PathVariable Long id,@PathVariable String relation) {
        return IdentityResponses.ok(identity.relations(resource,id,relation));
    }
    @PutMapping("/{id}/{relation:roles|permissions|menus}")
    public Object assign(@PathVariable String resource,@PathVariable Long id,@PathVariable String relation,@RequestBody JsonNode input) {
        if(input==null || !input.isArray() || input.size()>500)throw new IdentityException(400,"请提供最多500项的整数ID数组");
        List<Long> ids=new ArrayList<>();
        for(JsonNode item:input) {
            if(!item.isIntegralNumber() || !item.canConvertToLong() || item.asLong()<=0)throw new IdentityException(400,"关联ID必须为正整数");
            ids.add(item.asLong());
        }
        identity.assign(resource,id,relation,ids);return IdentityResponses.ok(null);
    }
    @PutMapping("/{id}/{action:activate|disable|lock|unlock|enable|show|hide}")
    public Object state(@PathVariable String resource,@PathVariable Long id,@PathVariable String action) {
        boolean supported=("users".equals(resource)&&Arrays.asList("activate","disable","lock","unlock").contains(action))
            || ("roles".equals(resource)&&Arrays.asList("enable","disable").contains(action))
            || ("menus".equals(resource)&&Arrays.asList("show","hide").contains(action))
            || ("teams".equals(resource)&&Arrays.asList("enable","disable").contains(action));
        if(!supported)throw new IdentityException(404,"不支持此状态操作");
        com.fasterxml.jackson.databind.node.ObjectNode input=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        input.put("status","lock".equals(action)?2:Arrays.asList("disable","hide").contains(action)?0:1);
        return IdentityResponses.ok(identity.update(resource,id,input));
    }
}

