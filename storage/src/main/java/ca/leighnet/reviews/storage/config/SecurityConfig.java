package ca.leighnet.reviews.storage.config;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Authentication only: callers present OAuth 2.0 bearer tokens (JWTs). What each
 * caller may do is decided by the services, from the token's role claims
 * (CurrentUser) and who owns or wrote the rows they change.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ActuatorProperties.class)
public class SecurityConfig {

	private static final String JWT = "spring.security.oauth2.resourceserver.jwt.";

	private static final String ACTUATOR_ROLE = "ACTUATOR";

	private static final String[] PUBLIC_READS = { "/context.jsonld", "/openapi.yml", "/categories", "/people",
			"/people/*", "/products", "/products/**", "/reviews/**", "/comments/**", "/images/*" };

	/**
	 * Actuator endpoints are served only on the management port
	 * ({@code management.server.port}), which is not published outside the
	 * Docker network. Even so, every container on that network can reach it,
	 * so only health (its status alone, unless signed in) and info are open;
	 * the rest need the actuator's own account ({@link ActuatorProperties}),
	 * over HTTP Basic, as Spring Boot Admin presents it. Bearer tokens are no
	 * use here, and the account is no use for the API. What that account may
	 * change is limited too: application.yml makes every endpoint read-only
	 * but loggers and caches.
	 */
	@Bean
	@Order(1)
	SecurityFilterChain actuator(HttpSecurity http, ActuatorProperties account) throws Exception {
		if (account.password() == null || account.password().isBlank()) {
			throw new IllegalStateException("reviews.actuator.password is empty: set ACTUATOR_PASSWORD"
					+ " (the repository root's .env, from scripts/secrets.sh)");
		}
		var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		var user = User.withUsername(account.username())
			.password(encoder.encode(account.password()))
			.roles(ACTUATOR_ROLE)
			.build();
		var provider = new DaoAuthenticationProvider(new InMemoryUserDetailsManager(user));
		provider.setPasswordEncoder(encoder);
		return http.securityMatcher(EndpointRequest.toAnyEndpoint())
			.authorizeHttpRequests(requests -> requests.requestMatchers(EndpointRequest.to("health", "info"))
				.permitAll()
				.anyRequest()
				.hasRole(ACTUATOR_ROLE))
			.httpBasic(Customizer.withDefaults())
			.authenticationManager(new ProviderManager(provider))
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.csrf(csrf -> csrf.disable())
			.build();
	}

	@Bean
	@Order(2)
	SecurityFilterChain api(HttpSecurity http) throws Exception {
		return http
			.authorizeHttpRequests(requests -> requests
				.requestMatchers("/people/me").authenticated()
				// HEAD too, so clients can read the validators (as for If-Match) without the body.
				.requestMatchers(HttpMethod.GET, PUBLIC_READS).permitAll()
				.requestMatchers(HttpMethod.HEAD, PUBLIC_READS).permitAll()
				.requestMatchers("/livez", "/readyz", "/error").permitAll()
				.anyRequest().authenticated())
			.oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.csrf(csrf -> csrf.disable())
			.cors(Customizer.withDefaults())
			.build();
	}

	/**
	 * The identity provider's tokens, configured with the standard
	 * {@code spring.security.oauth2.resourceserver.jwt.*} properties
	 * ({@code issuer-uri}, and {@code jwk-set-uri} where the keys are reached at
	 * another address, and {@code audiences}). In development, also tokens signed
	 * with {@code reviews.security.jwt-secret}, but only when
	 * {@code reviews.security.dev-tokens} turns them on: the secret is in every
	 * checkout's .env, and anyone holding it can sign a token for anyone.
	 */
	@Bean
	JwtDecoder jwtDecoder(ReviewsProperties properties, Environment environment) {
		String issuerUri = environment.getProperty(JWT + "issuer-uri");
		String jwkSetUri = environment.getProperty(JWT + "jwk-set-uri");
		List<String> audiences = Binder.get(environment)
			.bind(JWT + "audiences", Bindable.listOf(String.class))
			.orElse(List.of());
		var security = properties.security();
		String secret = security.jwtSecret();
		if (security.devTokens() && (secret == null || secret.isBlank())) {
			throw new IllegalStateException(
					"reviews.security.dev-tokens is on, but reviews.security.jwt-secret (JWT_SECRET) is empty");
		}
		return new BearerTokenDecoder(
				(issuerUri != null || jwkSetUri != null) ? BearerTokenDecoder.provider(issuerUri, jwkSetUri, audiences)
						: null,
				security.devTokens() ? BearerTokenDecoder.development(secret) : null);
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(ReviewsProperties properties) {
		var cors = new CorsConfiguration();
		cors.setAllowedOrigins(properties.cors().allowedOrigins());
		// The methods the API serves (OPTIONS is the preflight itself).
		cors.setAllowedMethods(List.of(HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.POST.name(),
				HttpMethod.PUT.name(), HttpMethod.DELETE.name()));
		// The request headers the API reads: the bearer token, content negotiation
		// (application/ld+json is not a CORS-safelisted Content-Type), and the
		// conditional headers for revalidation and optimistic concurrency.
		cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.ACCEPT, HttpHeaders.CONTENT_TYPE,
				HttpHeaders.IF_NONE_MATCH, HttpHeaders.IF_MODIFIED_SINCE, HttpHeaders.IF_MATCH,
				HttpHeaders.IF_UNMODIFIED_SINCE));
		// The validators, so a browser client can send them back as If-Match.
		cors.setExposedHeaders(List.of("Location", "Content-Location", "ETag", "Last-Modified"));
		var source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cors);
		return source;
	}

}
