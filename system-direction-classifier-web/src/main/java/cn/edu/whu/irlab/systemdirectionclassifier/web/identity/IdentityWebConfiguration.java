package cn.edu.whu.irlab.systemdirectionclassifier.web.identity;

import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import javax.servlet.http.*;
import java.net.URI;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Configuration
@RequiredArgsConstructor
public class IdentityWebConfiguration implements WebMvcConfigurer {
    private final IdentityService identity;
    public static String csrf(HttpSession session) {
        String token=(String)session.getAttribute("csrf");
        if(token==null) { token=UUID.randomUUID().toString()+UUID.randomUUID().toString();session.setAttribute("csrf",token); }
        return token;
    }
    private static boolean same(String a,String b) {
        return a!=null && b!=null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                CurrentActor.clear();
                String path=request.getRequestURI().substring(request.getContextPath().length());
                boolean safe=Arrays.asList("GET","HEAD","OPTIONS").contains(request.getMethod());
                if(!safe) {
                    String origin=request.getHeader("Origin");
                    if(origin!=null) {
                        try {
                            URI uri=URI.create(origin);
                            if(!Objects.equals(uri.getAuthority(),request.getHeader("Host")) || !Objects.equals(uri.getScheme(),request.getScheme()))
                                throw new IdentityException(403,"跨站请求已拒绝");
                        } catch(IllegalArgumentException e) { throw new IdentityException(403,"无效请求来源"); }
                    }
                    HttpSession session=request.getSession(false);
                    if(session==null || !same((String)session.getAttribute("csrf"),request.getHeader("X-CSRF-Token")))
                        throw new IdentityException(403,"请求校验失效，请刷新页面");
                }
                if("/api/auth/csrf".equals(path) && "GET".equals(request.getMethod()))return true;
                if("/api/auth/login".equals(path) && "POST".equals(request.getMethod()))return true;
                HttpSession session=request.getSession(false);
                if(session==null || !(session.getAttribute("userId") instanceof Long))throw new IdentityException(401,"请先登录");
                Long userId=(Long)session.getAttribute("userId");
                try {
                    String current=identity.credentialStamp(userId);
                    if(!same(current,(String)session.getAttribute("credentialStamp")))throw new IdentityException(401,"密码已变更，请重新登录");
                } catch(IdentityException e) {
                    session.invalidate();throw new IdentityException(401,"登录已失效，请重新登录");
                }
                CurrentActor.set(userId);
                response.setHeader("Cache-Control","no-store");
                return true;
            }
            @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception ex) {
                CurrentActor.clear();
            }
        }).addPathPatterns("/api/**");
    }
}

