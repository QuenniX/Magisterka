// Plik bez duplikatów metod – jeśli widzisz ten komentarz, masz aktualną wersję z dysku.
package pl.magisterka.backend.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mqtt")
public class MqttProperties {
    private String broker;
    private String clientIdPrefix;
    private String telemetryTopic;
    private String stateTopic;
    private String username;
    private String password;

    public String getBroker() { return broker; }
    public void setBroker(String broker) { this.broker = broker; }

    public String getClientIdPrefix() { return clientIdPrefix; }
    public void setClientIdPrefix(String clientIdPrefix) { this.clientIdPrefix = clientIdPrefix; }

    public String getTelemetryTopic() { return telemetryTopic; }
    public void setTelemetryTopic(String telemetryTopic) { this.telemetryTopic = telemetryTopic; }

    public String getStateTopic() { return stateTopic; }
    public void setStateTopic(String stateTopic) { this.stateTopic = stateTopic; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
