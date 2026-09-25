package org.isolatedareas.helphub.geo;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/demand")
public class DemandClusterController {
    private final DemandClusterService service;

    public DemandClusterController(DemandClusterService service) {
        this.service = service;
    }

    @GetMapping("/clusters")
    List<DemandClusterService.DemandCluster> clusters(
        @RequestParam(defaultValue = "1500") double radiusMeters,
        @RequestParam(defaultValue = "3") int minimumPoints
    ) {
        return service.clusters(radiusMeters, minimumPoints);
    }
}

