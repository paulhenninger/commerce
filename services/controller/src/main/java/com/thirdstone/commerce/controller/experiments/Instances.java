package com.thirdstone.commerce.controller.experiments;

import com.thirdstone.commerce.controller.Instance;
import org.springframework.stereotype.Component;

import java.util.List;

/** Instance control for experiments, with every change written to the recovery ledger first. */
@Component
public class Instances {

    private final com.thirdstone.commerce.controller.DockerClient docker;
    private final RecoveryLedger ledger;

    Instances(com.thirdstone.commerce.controller.DockerClient docker, RecoveryLedger ledger) {
        this.docker = docker;
        this.ledger = ledger;
    }

    List<Instance> of(String service) {
        return docker.list().stream().filter(i -> service.equals(i.service())).toList();
    }

    void act(Instance instance, String action) {
        if (!instance.controllable()) {
            throw new IllegalStateException(instance.service() + " is not controllable");
        }
        if (action.equals("stop") || action.equals("kill") || action.equals("pause")) {
            ledger.disturbed(instance.id(), action);
        }
        docker.act(instance.id(), action);
        if (action.equals("start") || action.equals("unpause")) {
            ledger.restored(instance.id());
        }
    }

    boolean healthy(String service, int expected) {
        return of(service).stream().filter(i -> i.state().equals("running")
                && (i.health() == null || i.health().equals("healthy"))).count() >= expected;
    }
}
