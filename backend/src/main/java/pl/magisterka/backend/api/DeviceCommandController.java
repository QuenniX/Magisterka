package pl.magisterka.backend.api;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pl.magisterka.backend.api.dto.DeviceCommandDto;
import pl.magisterka.backend.mqtt.MqttCommandPublisher;

@RestController
@RequestMapping("/api/devices")
public class DeviceCommandController {

    private final MqttCommandPublisher publisher;

    public DeviceCommandController(MqttCommandPublisher publisher) {
        this.publisher = publisher;
    }

    @PostMapping("/{deviceType}/{deviceId}/cmd")
    public ResponseEntity<?> sendCmd(
            @PathVariable String deviceType,
            @PathVariable String deviceId,
            @RequestBody DeviceCommandDto body
    ) throws MqttException {

        String cmd = body == null ? null : body.cmd;
        if (cmd == null || (!cmd.equalsIgnoreCase("START") && !cmd.equalsIgnoreCase("STOP"))) {
            return ResponseEntity.badRequest().body("cmd must be START or STOP");
        }

        publisher.publishCommand(deviceType, deviceId, cmd.toUpperCase());
        return ResponseEntity.ok().build();
    }
}
