package ca.leighnet.reviews.storage.config;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Decodes the bearer tokens of the identity provider (the identity project's
 * Keycloak) and, in development, tokens signed with a shared secret
 * ({@code scripts/dev-token.sh}). The identity provider signs with a key pair,
 * so an HMAC-signed token can only be a development token: the token's
 * algorithm picks the decoder.
 */
public final class BearerTokenDecoder implements JwtDecoder {

	private final JwtDecoder provider;

	private final JwtDecoder development;

	/**
	 * @param provider decodes the identity provider's tokens, or null if there is none
	 * @param development decodes development tokens, or null outside development
	 */
	public BearerTokenDecoder(JwtDecoder provider, JwtDecoder development) {
		if (provider == null && development == null) {
			throw new IllegalStateException("Configure spring.security.oauth2.resourceserver.jwt.issuer-uri "
					+ "(the identity provider) or, for development, reviews.security.dev-tokens with "
					+ "reviews.security.jwt-secret");
		}
		this.provider = provider;
		this.development = development;
	}

	@Override
	public Jwt decode(String token) throws JwtException {
		JWSAlgorithm algorithm;
		try {
			if (!(JWTParser.parse(token) instanceof SignedJWT signed)) {
				throw new BadJwtException("Bearer tokens must be signed");
			}
			JWSHeader header = signed.getHeader();
			algorithm = header.getAlgorithm();
		}
		catch (ParseException ex) {
			throw new BadJwtException("Malformed token", ex);
		}
		JwtDecoder decoder = JWSAlgorithm.Family.HMAC_SHA.contains(algorithm) ? development : provider;
		if (decoder == null) {
			throw new BadJwtException("Tokens signed with " + algorithm + " are not accepted here");
		}
		return decoder.decode(token);
	}

	/**
	 * The identity provider's tokens: signed with a key from its JWK set (or the
	 * issuer's, found by discovery), and valid only with {@link #providerValidator}.
	 */
	public static JwtDecoder provider(String issuerUri, String jwkSetUri, List<String> audiences) {
		NimbusJwtDecoder decoder = (jwkSetUri != null) ? NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()
				: NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
		decoder.setJwtValidator(providerValidator(issuerUri, audiences));
		return decoder;
	}

	/**
	 * Unexpired, from the issuer (when it is known), for one of the audiences
	 * (when any are configured), and naming a verified email: the service knows
	 * people by their email, so an unverified one could claim someone else's.
	 */
	public static OAuth2TokenValidator<Jwt> providerValidator(String issuerUri, List<String> audiences) {
		List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
		validators.add((issuerUri != null) ? JwtValidators.createDefaultWithIssuer(issuerUri) : JwtValidators.createDefault());
		if (!audiences.isEmpty()) {
			validators.add(new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
					aud -> aud != null && aud.stream().anyMatch(audiences::contains)));
		}
		validators.add(new JwtClaimValidator<Boolean>("email_verified", Boolean.TRUE::equals));
		return new DelegatingOAuth2TokenValidator<>(validators);
	}

	/** Development tokens: HS256 with the shared secret, nothing else checked but expiry. */
	public static JwtDecoder development(String secret) {
		return NimbusJwtDecoder
			.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
			.macAlgorithm(MacAlgorithm.HS256)
			.build();
	}

}
