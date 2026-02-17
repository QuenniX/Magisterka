package pl.magisterka.backend.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mqtt")
public class MqttProperties {
    private String broker;
    private String clientIdPrefix;
    private String telemetryTopic;
    private String stateTopic;

    public String getBroker() { return broker; }
    public void setBroker(String broker) { this.broker = broker; }

    public String getClientIdPrefix() { return clientIdPrefix; }
    public void setClientIdPrefix(String clientIdPrefix) { this.clientIdPrefix = clientIdPrefix; }

    public String getTelemetryTopic() { return telemetryTopic; }
    public void setTelemetryTopic(String telemetryTopic) { this.telemetryTopic = telemetryTopic; }

    public String getStateTopic() { return stateTopic; }
    public void setStateTopic(String stateTopic) { this.stateTopic = stateTopic; }
}
