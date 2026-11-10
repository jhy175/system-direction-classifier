package cn.edu.whu.irlab.systemdirectionclassifier.config;
import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
@Component
@Profile("mysql")
@RequiredArgsConstructor
public class IdentityBootstrap implements ApplicationRunner {
    private final IdentityService identity;
    @Value("${classifier.identity.bootstrap-login:}") private String login;
    @Value("${classifier.identity.bootstrap-password:}") private String password;
    @Override public void run(ApplicationArguments args) { identity.bootstrap(login,password); }
}

