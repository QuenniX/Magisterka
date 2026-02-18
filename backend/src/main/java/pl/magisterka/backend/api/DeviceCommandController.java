package pl.magisterka.backend.api;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pl.magisterka.backend.api.dto.DeviceCommandDto;
import pl.magisterka.backend.mqtt.MqttCommandPublisher;


@CrossOrigin(origins = "http://localhost:5173")
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

        if (body == null || body.cmd == null) {
            return ResponseEntity.badRequest().body("cmd must be provided");
        }

        publisher.publishCommand(deviceType, deviceId, body.cmd);

        return ResponseEntity.ok().build();
    }
}
