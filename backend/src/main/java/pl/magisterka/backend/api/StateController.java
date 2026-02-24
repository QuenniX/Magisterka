package pl.magisterka.backend.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.magisterka.backend.api.dto.LatestStateDto;
import pl.magisterka.backend.db.DeviceStateEventRepository;

import java.util.List;

@RestController
@RequestMapping("/api/state")
public class StateController {

    private final DeviceStateEventRepository repo;

    public StateController(DeviceStateEventRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/latest")
    public List<LatestStateDto> latest() {
        return repo.findLatestStatePerDevice()
                .stream()
                .map(e -> new LatestStateDto(
                        e.getDeviceId(),
                        e.getDeviceType(),
                        e.getState(),
                        e.getPhase(),
                        e.getTs()
                ))
                .toList();
    }
}