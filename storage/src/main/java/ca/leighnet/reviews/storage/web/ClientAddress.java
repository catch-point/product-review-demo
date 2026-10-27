package ca.leighnet.reviews.storage.web;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import ca.leighnet.reviews.storage.config.ReviewsProperties;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * The address of the client a request is for. That is the connection's peer,
 * unless the peer is one of the trusted proxies ({@code reviews.requests.trusted-proxies}):
 * then it is the last address in X-Forwarded-For that is not a trusted proxy
 * too. Anyone can send X-Forwarded-For, so from anyone else it is ignored.
 *
 * The forwarded-header filter (server.forward-headers-strategy: framework)
 * already answers getRemoteAddr() from X-Forwarded-For, whoever sent it, and
 * hides the header, so both are read from the container's own request beneath
 * it.
 */
class ClientAddress {

	// IPv4 and IPv6 literals only, so matching never looks a name up in DNS.
	private static final Pattern LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");

	private final List<IpAddressMatcher> trusted;

	ClientAddress(ReviewsProperties properties) {
		this(properties.requests().trustedProxies());
	}

	ClientAddress(List<String> trustedProxies) {
		this.trusted = trustedProxies.stream()
			.map(String::strip)
			.filter(range -> !range.isEmpty())
			.map(IpAddressMatcher::new)
			.toList();
	}

	String of(HttpServletRequest request) {
		var received = (HttpServletRequest) received(request);
		String peer = received.getRemoteAddr();
		if (!isTrusted(peer)) {
			return peer;
		}
		var headers = received.getHeaders("X-Forwarded-For");
		List<String> chain = (headers == null) ? List.of()
				: Collections.list(headers)
					.stream()
					.flatMap(header -> Arrays.stream(header.split(",")))
					.map(String::strip)
					.filter(address -> !address.isEmpty())
					.toList();
		for (int i = chain.size() - 1; i >= 0; i--) {
			if (!isTrusted(chain.get(i))) {
				return chain.get(i);
			}
		}
		return chain.isEmpty() ? peer : chain.get(0);
	}

	private boolean isTrusted(String address) {
		return LITERAL.matcher(address).matches() && trusted.stream().anyMatch(range -> range.matches(address));
	}

	/** The request as the servlet container received it. */
	private static ServletRequest received(ServletRequest request) {
		while (request instanceof ServletRequestWrapper wrapper) {
			request = wrapper.getRequest();
		}
		return request;
	}

}
