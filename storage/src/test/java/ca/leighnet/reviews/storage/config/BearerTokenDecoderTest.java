package ca.leighnet.reviews.storage.config;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class BearerTokenDecoderTest {

	private static final String ISSUER = "http://localhost:8180/realms/reviews";

	private static final String SECRET = "test-secret-0123456789abcdef0123456789abcdef";

	private static RSAPrivateKey privateKey;

	private static JwtDecoder provider;

	@BeforeAll
	static void keys() throws Exception {
		var generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		var pair = generator.generateKeyPair();
		privateKey = (RSAPrivateKey) pair.getPrivate();
		var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) pair.getPublic()).build();
		decoder.setJwtValidator(BearerTokenDecoder.providerValidator(ISSUER, List.of("storage")));
		provider = decoder;
	}

	@Test
	void acceptsTheIdentityProvidersTokens() throws Exception {
		var decoder = new BearerTokenDecoder(provider, BearerTokenDecoder.development(SECRET));

		var jwt = decoder.decode(providerToken(Map.of()));

		assertThat(jwt.getClaimAsString("email")).isEqualTo("olive@example.com");
	}

	@Test
	void acceptsDevelopmentTokensOnlyInDevelopment() throws Exception {
		String token = sign(new MACSigner(SECRET), JWSAlgorithm.HS256,
				claims(Map.of()).issuer(null).audience(List.of()).build());

		assertThat(new BearerTokenDecoder(provider, BearerTokenDecoder.development(SECRET)).decode(token)
			.getClaimAsString("email")).isEqualTo("olive@example.com");
		assertThatExceptionOfType(BadJwtException.class)
			.isThrownBy(() -> new BearerTokenDecoder(provider, null).decode(token))
			.withMessageContaining("HS256");
	}

	@Test
	void refusesTokensFromElsewhereForOthersOrWithUnverifiedEmails() {
		var decoder = new BearerTokenDecoder(provider, null);

		assertThatExceptionOfType(JwtValidationException.class)
			.isThrownBy(() -> decoder.decode(providerToken(Map.of("iss", "http://elsewhere.example/realms/reviews"))));
		assertThatExceptionOfType(JwtValidationException.class)
			.isThrownBy(() -> decoder.decode(providerToken(Map.of("aud", List.of("account")))));
		assertThatExceptionOfType(JwtValidationException.class)
			.isThrownBy(() -> decoder.decode(providerToken(Map.of("email_verified", false))));
	}

	@Test
	void refusesMalformedTokens() {
		var decoder = new BearerTokenDecoder(provider, null);

		assertThatExceptionOfType(BadJwtException.class).isThrownBy(() -> decoder.decode("not a token"));
		assertThatExceptionOfType(BadJwtException.class).isThrownBy(() -> decoder.decode("eyJhbGciOiJub25lIn0.e30."));
	}

	@Test
	void needsAnIdentityProviderOrADevelopmentSecret() {
		assertThatIllegalStateException().isThrownBy(() -> new BearerTokenDecoder(null, null))
			.withMessageContaining("issuer-uri");
	}

	private static String providerToken(Map<String, Object> overrides) throws Exception {
		return sign(new RSASSASigner(privateKey), JWSAlgorithm.RS256, claims(overrides).build());
	}

	private static JWTClaimsSet.Builder claims(Map<String, Object> overrides) {
		var claims = new JWTClaimsSet.Builder().subject("0b9b3c3e")
			.issuer(ISSUER)
			.audience(List.of("storage", "account"))
			.claim("email", "olive@example.com")
			.claim("email_verified", true)
			.issueTime(new Date())
			.expirationTime(Date.from(Instant.now().plusSeconds(300)));
		overrides.forEach(claims::claim);
		return claims;
	}

	private static String sign(JWSSigner signer, JWSAlgorithm algorithm, JWTClaimsSet claims) throws Exception {
		var jwt = new SignedJWT(new JWSHeader(algorithm), claims);
		jwt.sign(signer);
		return jwt.serialize();
	}

}
