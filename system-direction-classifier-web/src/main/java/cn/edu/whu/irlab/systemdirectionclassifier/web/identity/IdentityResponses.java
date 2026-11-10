package cn.edu.whu.irlab.systemdirectionclassifier.web.identity;
import java.util.*;
public final class IdentityResponses {
    private IdentityResponses() {}
    public static Map<String,Object> ok(Object data) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("success",true);result.put("data",data);return result;
    }
}

