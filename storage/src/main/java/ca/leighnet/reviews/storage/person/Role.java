package ca.leighnet.reviews.storage.person;

import java.util.Locale;

/**
 * What a person chose to be when they registered with the identity provider,
 * carried by the {@code reviews.security.role-claim} of their token. Only
 * owners add products; only reviewers review them.
 */
public enum Role {

	REVIEWER, OWNER;

	/** The claim's value: "reviewer" or "owner". */
	public String claimValue() {
		return name().toLowerCase(Locale.ROOT);
	}

}
