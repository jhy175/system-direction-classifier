package cn.edu.whu.irlab.systemdirectionclassifier.web.identity;

import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {
    private final IdentityService identity;
    private final Map<String,FailureWindow> failures=new ConcurrentHashMap<>();
    private static final class FailureWindow {
        long start=System.currentTimeMillis();int count;
    }
    private void checkRate(String ip) {
        synchronized(failures) {
            long now=System.currentTimeMillis();
            failures.entrySet().removeIf(e->now-e.getValue().start>300000);
            if(failures.size()>10000)throw new IdentityException(429,"登录请求过多，请稍后再试");
            FailureWindow window=failures.get(ip);
            if(window!=null && window.count>=20)throw new IdentityException(429,"登录失败次数过多，请5分钟后再试");
        }
    }
    private String text(JsonNode node,String key) {
        if(node==null || !node.isObject() || !node.has(key) || !node.get(key).isTextual())throw new IdentityException(400,key+"必须为字符串");
        return node.get(key).asText();
    }
    @GetMapping("/csrf") public Object csrf(HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        return IdentityResponses.ok(Collections.singletonMap("csrfToken",IdentityWebConfiguration.csrf(request.getSession(true))));
    }
    @PostMapping("/login") public Object login(@RequestBody JsonNode input,HttpServletRequest request,HttpServletResponse response) {
        String ip=request.getRemoteAddr();checkRate(ip);
        Map<String,Object> user;
        try { user=identity.login(text(input,"login"),text(input,"password"),ip); }
        catch(IdentityException e) {
            if(e.getStatus()==401)synchronized(failures){ failures.computeIfAbsent(ip,k->new FailureWindow()).count++; }
            throw e;
        }
        synchronized(failures){failures.remove(ip);}
        HttpSession previous=request.getSession(false);if(previous!=null)previous.invalidate();
        HttpSession session=request.getSession(true);Long id=((Number)user.get("id")).longValue();
        session.setAttribute("userId",id);session.setAttribute("credentialStamp",identity.credentialStamp(id));
        session.setMaxInactiveInterval(1800);
        Map<String,Object> data=new LinkedHashMap<>();data.put("user",user);data.put("csrfToken",IdentityWebConfiguration.csrf(session));
        response.setHeader("Cache-Control","no-store");
        return IdentityResponses.ok(data);
    }
    @PostMapping("/logout") public Object logout(HttpServletRequest request) {
        HttpSession session=request.getSession(false);if(session!=null)session.invalidate();return IdentityResponses.ok(null);
    }
    @GetMapping("/me") public Object me() { return IdentityResponses.ok(identity.me()); }
    @RequestMapping(value="/me",method={RequestMethod.PUT,RequestMethod.PATCH})
    public Object update(@RequestBody JsonNode input) { return IdentityResponses.ok(identity.updateProfile(input)); }
    @PutMapping("/password") public Object password(@RequestBody JsonNode input,HttpServletRequest request) {
        identity.changePassword(text(input,"oldPassword"),text(input,"newPassword"));
        request.getSession(false).invalidate();return IdentityResponses.ok(null);
    }
}

