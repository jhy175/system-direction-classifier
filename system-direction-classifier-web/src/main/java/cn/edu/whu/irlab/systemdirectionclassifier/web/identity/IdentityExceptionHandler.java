package cn.edu.whu.irlab.systemdirectionclassifier.web.identity;
import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.*;
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IdentityExceptionHandler {
    private ResponseEntity<Object> fail(int code,String message) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("success",false);result.put("message",message);
        return ResponseEntity.status(code).body(result);
    }
    @ExceptionHandler(IdentityException.class) public ResponseEntity<Object> identity(IdentityException e) { return fail(e.getStatus(),e.getMessage()); }
    @ExceptionHandler(DataIntegrityViolationException.class) public ResponseEntity<Object> integrity(DataIntegrityViolationException e) { return fail(409,"唯一字段重复或字段值不符合数据库约束"); }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Object> malformed(Exception e) { return fail(400,"请求格式或字段类型不正确"); }
}

