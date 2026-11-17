package com.thirdstone.commerce.controller;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Control of containers has no business outside dev; refuse to run there. */
@Component
class DevOnly {

    DevOnly(Environment env) {
        String environment = env.getProperty("commerce.environment", "dev");
        if (!"dev".equalsIgnoreCase(environment)) {
            throw new IllegalStateException("The controller exists only in dev; refusing to start in '" + environment + "'");
        }
    }
}
