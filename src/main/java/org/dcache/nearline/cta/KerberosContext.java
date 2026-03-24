package org.dcache.cta;

import com.google.common.base.Strings;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import javax.security.auth.kerberos.KerberosTicket;
import java.security.PrivilegedExceptionAction;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class KerberosContext {

    private final String keytab;
    private final String principal;

    private final Object lock = new Object();

    private volatile LoginContext loginContext;
    private volatile Subject subject;

    public KerberosContext(String keytab, String principal) {
        this.keytab = keytab;
        this.principal = principal;
    }

    public boolean isEnabled() {
        return !Strings.isNullOrEmpty(keytab) && !Strings.isNullOrEmpty(principal);
    }

    public void login() throws LoginException {
        synchronized (lock) {
            loginContext = new LoginContext("cta-kerberos", null, (CallbackHandler) null,
                  buildConfiguration());
            loginContext.login();
            subject = loginContext.getSubject();
        }
    }

    public <T> T doAs(PrivilegedExceptionAction<T> action) throws Exception {
        Subject s = getValidSubject();
        return Subject.doAs(s, action);
    }

    private Subject getValidSubject() throws LoginException {
        synchronized (lock) {
            if (!isTicketValid()) {
                if (loginContext != null) {
                    try {
                        loginContext.logout();
                    } catch (LoginException ignored) {
                    }
                }
                loginContext = new LoginContext("cta-kerberos", null, (CallbackHandler) null,
                      buildConfiguration());
                loginContext.login();
                subject = loginContext.getSubject();
            }
            return subject;
        }
    }

    private boolean isTicketValid() {
        if (subject == null) {
            return false;
        }

        Set<KerberosTicket> tickets = subject.getPrivateCredentials(KerberosTicket.class);
        long nowPlusSkew = System.currentTimeMillis() + 60_000L;

        for (KerberosTicket t : tickets) {
            Date end = t.getEndTime();
            if (t.isCurrent() && end != null && end.getTime() > nowPlusSkew) {
                return true;
            }
        }
        return false;
    }

    private Configuration buildConfiguration() {
        return new Configuration() {
            @Override
            public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                Map<String,Object> options = new HashMap<>();
                options.put("useKeyTab", "true");
                options.put("keyTab", keytab);
                options.put("principal", principal);
                options.put("storeKey", "true");
                options.put("doNotPrompt", "true");
                options.put("useTicketCache", "false");
                options.put("refreshKrb5Config", "true");
                options.put("isInitiator", "true");

                return new AppConfigurationEntry[] {
                    new AppConfigurationEntry(
                          "com.sun.security.auth.module.Krb5LoginModule",
                          AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                          options)
                };
            }
        };
    }
}
