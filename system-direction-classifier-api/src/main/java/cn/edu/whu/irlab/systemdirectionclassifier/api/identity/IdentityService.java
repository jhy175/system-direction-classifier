package cn.edu.whu.irlab.systemdirectionclassifier.api.identity;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
public interface IdentityService {
    Map<String,Object> login(String login, String password, String ip);
    Map<String,Object> sessionUser(Long userId);
    String credentialStamp(Long userId);
    Map<String,Object> me();
    Map<String,Object> updateProfile(JsonNode input);
    void changePassword(String oldPassword, String newPassword);
    Map<String,Object> list(String resource, Map<String,String> query);
    Map<String,Object> get(String resource, Long id);
    Map<String,Object> create(String resource, JsonNode input);
    Map<String,Object> update(String resource, Long id, JsonNode input);
    void remove(String resource, Long id);
    List<Map<String,Object>> relations(String resource, Long id, String relation);
    void assign(String resource, Long id, String relation, List<Long> ids);
    List<Map<String,Object>> tree(String resource);
    List<Map<String,Object>> members(Long teamId);
    Map<String,Object> saveMember(Long teamId, Long userId, JsonNode input);
    void removeMember(Long teamId, Long userId);
    void transferTeam(Long teamId, Long userId);
    void bootstrap(String login, String password);
}

