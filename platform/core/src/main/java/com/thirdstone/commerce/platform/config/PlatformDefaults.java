package com.thirdstone.commerce.platform.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Adds the platform's default settings beneath everything else.
 *
 * <p>A file pulled in with {@code spring.config.import} takes precedence over the
 * file that imports it, which is the opposite of what a default should do. Added
 * here, last in the environment, these files lose to every other source: the
 * service's own application.yml, profiles, environment variables, test properties.
 * {@code messaging-defaults.yml} is applied only when platform:messaging is on the
 * classpath.
 */
public class PlatformDefaults implements EnvironmentPostProcessor {

    private static final String[] FILES = {"messaging-defaults.yml", "platform-defaults.yml"};

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String file : FILES) {
            ClassPathResource resource = new ClassPathResource(file);
            if (!resource.exists()) {
                continue;
            }
            try {
                for (PropertySource<?> source : loader.load("commerce:" + file, resource)) {
                    environment.getPropertySources().addLast(source);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + file, e);
            }
        }
    }
}
