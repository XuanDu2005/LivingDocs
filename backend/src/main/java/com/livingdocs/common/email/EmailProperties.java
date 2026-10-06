package com.livingdocs.common.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the {@link EmailService}.
 *
 * <p>Backed by the {@code app.mail.*} keys in {@code application.yml}.
 */
@ConfigurationProperties(prefix = "app.mail")
public class EmailProperties {

    private String from = "no-reply@livingdocs.local";
    private String fromName = "LivingDocs";
    private Smtp smtp = new Smtp();

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }

    public Smtp getSmtp() { return smtp; }
    public void setSmtp(Smtp smtp) { this.smtp = smtp; }

    public static class Smtp {
        private String host;
        private int port = 587;
        private String username;
        private String password;
        private boolean starttls = true;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public boolean isStarttls() { return starttls; }
        public void setStarttls(boolean starttls) { this.starttls = starttls; }
    }
}
