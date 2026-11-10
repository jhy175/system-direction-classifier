package cn.edu.whu.irlab.systemdirectionclassifier.web.identity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
@Controller
public class IdentityPageController {
    @GetMapping({"/identity","/identity/"})
    public String index() { return "forward:/identity/index.html"; }
}
