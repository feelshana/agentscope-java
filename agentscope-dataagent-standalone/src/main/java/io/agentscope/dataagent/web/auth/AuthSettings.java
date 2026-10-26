package io.agentscope.dataagent.web.auth;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Public entry policy; production never exposes password authentication. */
@Component
public class AuthSettings {
    private final boolean passwordLogin;
    private final boolean biLogin;
    private final String sharedKey;

    public AuthSettings(Environment environment) {
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        passwordLogin =
                !production
                        && environment.getProperty(
                                "dataagent.auth.password-login-enabled", Boolean.class, true);
        biLogin =
                production
                        || environment.getProperty(
                                "dataagent.auth.bi.enabled", Boolean.class, false);
        sharedKey = environment.getProperty("dataagent.auth.bi.shared-key", "");
    }

    public boolean passwordLogin() {
        return passwordLogin;
    }

    public boolean biLogin() {
        return biLogin;
    }

    public String sharedKey() {
        return sharedKey;
    }
}
