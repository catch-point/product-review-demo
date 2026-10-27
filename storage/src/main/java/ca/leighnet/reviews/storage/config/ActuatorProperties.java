package ca.leighnet.reviews.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The one account the actuator answers on the management port, over HTTP
 * Basic (SecurityConfig): Spring Boot Admin, which is given it with the
 * service's registration (application.yml's spring.boot.admin.client), or a
 * person with curl. Its password is ACTUATOR_PASSWORD, from the repository
 * root's .env (scripts/secrets.sh).
 */
@ConfigurationProperties("reviews.actuator")
public record ActuatorProperties(@DefaultValue("actuator") String username, String password) {
}
