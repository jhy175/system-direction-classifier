package cn.edu.whu.irlab.systemdirectionclassifier.service.impl;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityService;
import org.ylzl.eden.spring.framework.dto.PageResult;
import java.util.*;
import java.util.stream.Collectors;
@Component @RequiredArgsConstructor
public class IdentityLegacyAdapter {
    final IdentityService identity;
    private final ObjectMapper json;
    JsonNode input(Object dto) {
        ObjectNode node=json.valueToTree(dto);List<String> remove=new ArrayList<>();
        node.fields().forEachRemaining(e->{if(e.getValue().isNull())remove.add(e.getKey());});
        remove.forEach(node::remove);
        if(node.has("preferences") && node.get("preferences").isTextual()) {
            try { node.set("preferences",json.readTree(node.get("preferences").asText())); }
            catch(Exception e) { throw new cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityException(400,"preferences格式不正确"); }
        }
        return node;
    }
    <T>T convert(Object value,Class<T> type) { return json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).convertValue(value,type); }
    <T>List<T> list(Object value,Class<T> type) { return ((List<?>)value).stream().map(v->convert(v,type)).collect(Collectors.toList()); }
    <T>PageResult<T> page(String resource,Object query,Class<T> type) {
        Map<String,String> params=new HashMap<>();
        json.valueToTree(query).fields().forEachRemaining(e->{if(!e.getValue().isNull())params.put(e.getKey(),e.getValue().asText());});
        Map<String,Object> page=identity.list(resource,params);
        return PageResult.build(list(page.get("items"),type),((Number)page.get("total")).intValue());
    }
    void state(String resource,Long id,int state) { ObjectNode node=json.createObjectNode();node.put("status",state);identity.update(resource,id,node); }
}
