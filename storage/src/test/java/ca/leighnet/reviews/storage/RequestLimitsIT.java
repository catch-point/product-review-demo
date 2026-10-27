package ca.leighnet.reviews.storage;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Request limits through the servlet container: request bodies as MockMvc
 * cannot send them (chunked, without a Content-Length), and rate limits per
 * client. This test is the trusted proxy, so its X-Forwarded-For names each
 * test's clients.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "reviews.security.jwt-secret=" + RequestLimitsIT.SECRET, "reviews.requests.trusted-proxies=127.0.0.1",
				"reviews.rate-limits.reads-per-minute=5", "reviews.rate-limits.writes-per-minute=6" })
@Import(TestcontainersConfiguration.class)
class RequestLimitsIT {

	static final String SECRET = "test-secret-0123456789abcdef0123456789abcdef";

	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	@LocalServerPort
	private int port;

	@Autowired
	private JsonMapper json;

	@Test
	void chunkedBodiesStopAtTheLimit() throws Exception {
		String token = token("owner-chunked@example.com", "owner");
		assertThat(send(token, "/people", "{\"type\":\"Person\",\"name\":\"Chunked\"}", false).statusCode())
			.isEqualTo(201);

		String product = "{\"type\":\"Product\",\"name\":\"Chunked\"}";
		assertThat(send(token, "/products", product, true).statusCode()).isEqualTo(201);

		// Short strings, as one long one would be refused (400) as soon as it passed 20000 characters.
		String padding = String.join(",", Collections.nCopies(100, "\"" + "x".repeat(1000) + "\""));
		String padded = "{\"type\":\"Product\",\"padding\":[" + padding + "],\"name\":\"Large\"}";
		var refused = send(token, "/products", padded, true);
		assertThat(refused.statusCode()).isEqualTo(413);
		assertThat(refused.headers().firstValue("Content-Type")).hasValue("application/problem+json");
		assertThat(json.readTree(refused.body()).path("detail").asString())
			.isEqualTo("Request bodies may be at most 64 KB");
	}

	@Test
	void limitsEachClientsReads() throws Exception {
		for (int i = 0; i < 5; i++) {
			assertThat(get("/categories", "203.0.113.1").statusCode()).isEqualTo(200);
		}
		var limited = get("/categories", "203.0.113.1");
		assertThat(limited.statusCode()).isEqualTo(429);
		assertThat(limited.headers().firstValue("Content-Type")).hasValue("application/problem+json");
		assertThat(limited.headers().firstValue("Retry-After").map(Integer::parseInt)).hasValueSatisfying(
				seconds -> assertThat(seconds).isBetween(1, 60));
		assertThat(json.readTree(limited.body()).path("detail").asString())
			.isEqualTo("Too many reads: at most 5 a minute");

		// Another client has its own limit.
		assertThat(get("/categories", "203.0.113.2").statusCode()).isEqualTo(200);
		// The client is the last address before the trusted proxies: what the
		// client itself put first in the header counts for nothing.
		assertThat(get("/categories", "198.51.100.7, 203.0.113.1").statusCode()).isEqualTo(429);
		assertThat(get("/categories", "203.0.113.1, 198.51.100.7").statusCode()).isEqualTo(200);
	}

	@Test
	void limitsEachPersonsWritesWhereverTheyConnectFrom() throws Exception {
		String token = token("owner-writes@example.com", "owner");
		assertThat(send(token, "/people", "{\"type\":\"Person\",\"name\":\"Writer\"}", false).statusCode())
			.isEqualTo(201);
		for (int i = 0; i < 5; i++) {
			var request = post(token, "/products", "{\"type\":\"Product\",\"name\":\"Writer " + i + "\"}")
				.header("X-Forwarded-For", "203.0.113." + (10 + i))
				.build();
			assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(201);
		}
		var limited = send(token, "/products", "{\"type\":\"Product\",\"name\":\"One too many\"}", false);
		assertThat(limited.statusCode()).isEqualTo(429);
		assertThat(json.readTree(limited.body()).path("detail").asString())
			.isEqualTo("Too many writes: at most 6 a minute");

		// Someone else may still write, from the same address.
		String other = token("owner-other-writer@example.com", "owner");
		assertThat(send(other, "/people", "{\"type\":\"Person\",\"name\":\"Other\"}", false).statusCode())
			.isEqualTo(201);
	}

	@Test
	void pagesNoDeeperThanTheOffsetLimit() throws Exception {
		assertThat(get("/products?offset=10000", "203.0.113.50").statusCode()).isEqualTo(200);
		assertThat(get("/products?offset=10001", "203.0.113.50").statusCode()).isEqualTo(400);
	}

	@Test
	void refusesLargeHeaders() throws Exception {
		var request = HttpRequest.newBuilder(uri("/products")).header("X-Padding", "x".repeat(10 * 1024)).build();
		assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
	}

	private HttpResponse<String> send(String token, String path, String body, boolean chunked) throws Exception {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		// A publisher of unknown length is sent chunked.
		var publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
				: HttpRequest.BodyPublishers.ofByteArray(bytes);
		return http.send(post(token, path, publisher).build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpRequest.Builder post(String token, String path, String body) {
		return post(token, path, HttpRequest.BodyPublishers.ofString(body));
	}

	private HttpRequest.Builder post(String token, String path, HttpRequest.BodyPublisher body) {
		return HttpRequest.newBuilder(uri(path))
			.header("Authorization", "Bearer " + token)
			.header("Content-Type", "application/ld+json")
			.POST(body);
	}

	private HttpResponse<String> get(String path, String forwardedFor) throws Exception {
		var request = HttpRequest.newBuilder(uri(path)).header("X-Forwarded-For", forwardedFor).build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://127.0.0.1:" + port + path);
	}

	private static String token(String email, String role) throws Exception {
		var claims = new JWTClaimsSet.Builder().subject(email)
			.claim("email", email)
			.claim("role", role)
			.expirationTime(Date.from(Instant.now().plusSeconds(300)))
			.build();
		var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
		return jwt.serialize();
	}

}
