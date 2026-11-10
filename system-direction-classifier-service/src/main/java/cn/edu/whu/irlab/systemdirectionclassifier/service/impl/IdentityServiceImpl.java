package cn.edu.whu.irlab.systemdirectionclassifier.service.impl;
import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityService;
import cn.edu.whu.irlab.systemdirectionclassifier.manager.identity.IdentityManager;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service
@RequiredArgsConstructor
@Transactional(rollbackFor=Exception.class)
public class IdentityServiceImpl implements IdentityService {
    private final IdentityManager manager;
    @Override public Map<String,Object> login(String login,String password,String ip) { return manager.login(login,password,ip); }
    @Override public Map<String,Object> sessionUser(Long userId) { return manager.sessionUser(userId); }
    @Override public String credentialStamp(Long userId) { return manager.credentialStamp(userId); }
    @Override public Map<String,Object> me() { return manager.me(); }
    @Override public Map<String,Object> updateProfile(JsonNode input) { return manager.updateProfile(input); }
    @Override public void changePassword(String oldPassword,String newPassword) { manager.changePassword(oldPassword,newPassword); }
    @Override public Map<String,Object> list(String resource,Map<String,String> query) { return manager.list(resource,query); }
    @Override public Map<String,Object> get(String resource,Long id) { return manager.get(resource,id); }
    @Override public Map<String,Object> create(String resource,JsonNode input) { return manager.create(resource,input); }
    @Override public Map<String,Object> update(String resource,Long id,JsonNode input) { return manager.update(resource,id,input); }
    @Override public void remove(String resource,Long id) { manager.remove(resource,id); }
    @Override public List<Map<String,Object>> relations(String resource,Long id,String relation) { return manager.relations(resource,id,relation); }
    @Override public void assign(String resource,Long id,String relation,List<Long> ids) { manager.assign(resource,id,relation,ids); }
    @Override public List<Map<String,Object>> tree(String resource) { return manager.tree(resource); }
    @Override public List<Map<String,Object>> members(Long teamId) { return manager.members(teamId); }
    @Override public Map<String,Object> saveMember(Long teamId,Long userId,JsonNode input) { return manager.saveMember(teamId,userId,input); }
    @Override public void removeMember(Long teamId,Long userId) { manager.removeMember(teamId,userId); }
    @Override public void transferTeam(Long teamId,Long userId) { manager.transferTeam(teamId,userId); }
    @Override public void bootstrap(String login,String password) { manager.bootstrap(login,password); }
}

