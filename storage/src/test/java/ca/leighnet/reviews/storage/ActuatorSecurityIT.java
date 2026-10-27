package ca.leighnet.reviews.storage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The actuator on its own port, as any container on the Docker network
 * reaches it: open for health (its status alone) and info, and otherwise only
 * to the actuator's account (src/test/resources/application.properties), which
 * may change log levels and clear caches but is no use for the API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "management.server.port=0", "reviews.security.jwt-secret=" + ActuatorSecurityIT.SECRET })
@Import(TestcontainersConfiguration.class)
class ActuatorSecurityIT {

	static final String SECRET = "test-secret-0123456789abcdef0123456789abcdef";

	private static final String ACCOUNT = basic("actuator", "a-test-actuator-password");

	private static final String LOGGER = "/actuator/loggers/ca.leighnet.reviews.storage.ActuatorSecurityIT";

	private final HttpClient http = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@LocalManagementPort
	private int managementPort;

	@Autowired
	private JsonMapper json;

	@Test
	void onlyHealthAndInfoAreOpen() throws Exception {
		assertThat(get("/actuator/loggers", null).statusCode()).isEqualTo(401);
		assertThat(get("/actuator/env", null).statusCode()).isEqualTo(401);
		assertThat(get("/actuator/info", null).statusCode()).isEqualTo(200);

		var health = get("/actuator/health", null);
		assertThat(health.statusCode()).isEqualTo(200);
		assertThat(json.readTree(health.body()).has("components")).as("details only when signed in").isFalse();
	}

	@Test
	void theActuatorsAccountReadsEverything() throws Exception {
		assertThat(get("/actuator/loggers", ACCOUNT).statusCode()).isEqualTo(200);
		assertThat(get("/actuator/env", ACCOUNT).statusCode()).isEqualTo(200);

		var health = get("/actuator/health", ACCOUNT);
		assertThat(json.readTree(health.body()).path("components").has("db")).isTrue();
	}

	@Test
	void theActuatorsAccountChangesLogLevelsAndClearsCaches() throws Exception {
		assertThat(send("POST", LOGGER, ACCOUNT, "{\"configuredLevel\":\"DEBUG\"}").statusCode()).isEqualTo(204);
		assertThat(json.readTree(get(LOGGER, ACCOUNT).body()).path("configuredLevel").asString()).isEqualTo("DEBUG");
		assertThat(send("POST", LOGGER, ACCOUNT, "{\"configuredLevel\":null}").statusCode()).isEqualTo(204);
		assertThat(send("DELETE", "/actuator/caches", ACCOUNT, null).statusCode()).isEqualTo(204);
	}

	@Test
	void nobodyElseChangesAnything() throws Exception {
		assertThat(send("POST", LOGGER, null, "{\"configuredLevel\":\"DEBUG\"}").statusCode()).isEqualTo(401);
		assertThat(send("POST", LOGGER, basic("actuator", "a-guess"), "{\"configuredLevel\":\"DEBUG\"}")
			.statusCode()).isEqualTo(401);
		assertThat(send("DELETE", "/actuator/caches", null, null).statusCode()).isEqualTo(401);
		// No level was set: the logger has none of its own.
		assertThat(json.readTree(get(LOGGER, ACCOUNT).body()).has("configuredLevel")).isFalse();
	}

	@Test
	void bearerTokensAndTheActuatorsAccountDoNotCross() throws Exception {
		String admin = "Bearer " + token("admin-actuator@example.com");
		assertThat(get("/actuator/loggers", admin).statusCode()).isEqualTo(401);

		var me = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/people/me"))
			.header("Authorization", ACCOUNT)
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(me.statusCode()).isEqualTo(401);
	}

	private HttpResponse<String> get(String path, String authorization) throws Exception {
		return send("GET", path, authorization, null);
	}

	private HttpResponse<String> send(String method, String path, String authorization, String body)
			throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + path))
			.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(body));
		if (body != null) {
			request.header("Content-Type", "application/json");
		}
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String basic(String username, String password) {
		return "Basic " + Base64.getEncoder()
			.encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
	}

	/** A development token with the admin realm role. */
	private static String token(String email) throws Exception {
		var claims = new JWTClaimsSet.Builder().subject(email)
			.claim("email", email)
			.claim("role", "owner")
			.claim("roles", List.of("admin"))
			.expirationTime(Date.from(Instant.now().plusSeconds(300)))
			.build();
		var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
		return jwt.serialize();
	}

}
