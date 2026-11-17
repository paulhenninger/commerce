package com.thirdstone.commerce.simulators;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The chaos lab's switches (and smoke.sh's). */
@RestController
@RequestMapping("/admin/faults")
class FaultController {

    private static final Logger log = LoggerFactory.getLogger(FaultController.class);

    private final Faults faults;

    FaultController(Faults faults) {
        this.faults = faults;
    }

    @GetMapping
    Map<String, Faults.Setting> all() {
        return faults.all();
    }

    @PutMapping("/{target}")
    Map<String, Faults.Setting> set(@PathVariable String target, @RequestBody Faults.Setting setting) {
        faults.set(target, setting);
        log.atWarn().addKeyValue("target", target).addKeyValue("latencyMs", setting.latencyMs())
                .addKeyValue("errorRate", setting.errorRate()).addKeyValue("outage", setting.outage())
                .log("Simulator faults changed");
        return faults.all();
    }
}
