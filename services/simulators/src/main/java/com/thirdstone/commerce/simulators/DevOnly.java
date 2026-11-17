package com.thirdstone.commerce.simulators;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** A fake payment provider has no business outside dev; refuse to run there. */
@Component
class DevOnly {

    DevOnly(Environment env) {
        String environment = env.getProperty("commerce.environment", "dev");
        if (!"dev".equalsIgnoreCase(environment)) {
            throw new IllegalStateException("The simulators exist only in dev; refusing to start in '" + environment + "'");
        }
    }
}
