package com.thirdstone.commerce.testkit;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Test data and chaos tooling have no business outside dev; refuse to run there. */
@Component
class DevOnly {

    DevOnly(Environment env) {
        String environment = env.getProperty("commerce.environment", "dev");
        if (!"dev".equalsIgnoreCase(environment)) {
            throw new IllegalStateException("The testkit exists only in dev; refusing to start in '" + environment + "'");
        }
    }
}
