package ca.leighnet.reviews.storage.person;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import ca.leighnet.reviews.storage.config.ReviewsProperties;
import ca.leighnet.reviews.storage.web.ApiException;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * The caller, identified by the email claim of their bearer token, with the
 * role they registered as and whether they are a site administrator.
 */
@Component
public class CurrentUser {

	private final PersonRepository people;

	private final ReviewsProperties.Security security;

	CurrentUser(PersonRepository people, ReviewsProperties properties) {
		this.people = people;
		this.security = properties.security();
	}

	private static Optional<Jwt> token() {
		if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token) {
			return Optional.of(token.getToken());
		}
		return Optional.empty();
	}

	public Optional<String> email() {
		return token().map(token -> token.getClaimAsString(security.emailClaim()))
			.map(email -> email.toLowerCase(Locale.ROOT));
	}

	public String requireEmail() {
		return email()
			.orElseThrow(() -> ApiException.forbidden("The bearer token has no '" + security.emailClaim() + "' claim"));
	}

	/** The caller's person_id, if they have registered. */
	public Optional<Long> personId() {
		return email().flatMap(people::findIdByEmail);
	}

	public long requirePersonId() {
		requireEmail();
		return personId().orElseThrow(() -> ApiException.forbidden("Register with POST /people first"));
	}

	public boolean hasRole(Role role) {
		return token().map(token -> token.getClaimAsString(security.roleClaim()))
			.filter(role.claimValue()::equals)
			.isPresent();
	}

	public void requireRole(Role role) {
		if (!hasRole(role)) {
			throw ApiException.forbidden("Only " + role.claimValue() + "s may do this");
		}
	}

	/**
	 * Whether the token's {@code roles-claim} lists the {@code admin-role}. No
	 * operation gives administrators more than anyone else yet; they cannot
	 * change anyone's data.
	 */
	public boolean isAdmin() {
		return token().map(token -> token.getClaim(security.rolesClaim()))
			.filter(List.class::isInstance)
			.map(roles -> ((List<?>) roles).contains(security.adminRole()))
			.orElse(false);
	}

}
