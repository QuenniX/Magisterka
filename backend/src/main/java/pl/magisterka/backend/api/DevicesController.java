package pl.magisterka.backend.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.magisterka.backend.api.dto.DeviceDto;
import pl.magisterka.backend.db.DeviceStateEventRepository;

import java.util.List;

@RestController
@RequestMapping("/api")
public class DevicesController {

    private final DeviceStateEventRepository stateRepo;

    public DevicesController(DeviceStateEventRepository stateRepo) {
        this.stateRepo = stateRepo;
    }

    @GetMapping("/devices")
    public List<DeviceDto> devices() {
        return stateRepo.findDistinctDevices()
                .stream()
                .map(row -> new DeviceDto((String) row[0], (String) row[1]))
                .toList();
    }
}
