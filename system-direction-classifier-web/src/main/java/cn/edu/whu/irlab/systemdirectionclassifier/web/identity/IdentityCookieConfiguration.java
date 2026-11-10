package cn.edu.whu.irlab.systemdirectionclassifier.web.identity;
import io.undertow.server.handlers.SameSiteCookieHandler;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;
/** Boot 2.4 does not expose the newer same-site cookie property; configure Undertow directly. */
@Configuration
public class IdentityCookieConfiguration implements WebServerFactoryCustomizer<UndertowServletWebServerFactory> {
    @Override public void customize(UndertowServletWebServerFactory factory) {
        factory.addDeploymentInfoCustomizers(info->info.addInitialHandlerChainWrapper(next->new SameSiteCookieHandler(next,"Strict")));
    }
}
