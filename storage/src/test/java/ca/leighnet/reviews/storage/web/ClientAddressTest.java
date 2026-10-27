package ca.leighnet.reviews.storage.web;

import java.util.List;

import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressTest {

	private final ClientAddress addresses = new ClientAddress(List.of("10.0.0.5", "172.31.250.0/28", "::1"));

	@Test
	void isThePeerUnlessThePeerIsATrustedProxy() {
		assertThat(addresses.of(request("198.51.100.7", "203.0.113.1"))).isEqualTo("198.51.100.7");
		assertThat(addresses.of(request("10.0.0.5", "203.0.113.1"))).isEqualTo("203.0.113.1");
		assertThat(addresses.of(request("::1", "2001:db8::1"))).isEqualTo("2001:db8::1");
		assertThat(addresses.of(request("10.0.0.5"))).isEqualTo("10.0.0.5");
	}

	@Test
	void isTheLastAddressBeforeTheTrustedProxies() {
		// What the client wrote first in the header is theirs to make up.
		assertThat(addresses.of(request("10.0.0.5", "1.1.1.1, 203.0.113.1, 172.31.250.10"))).isEqualTo("203.0.113.1");
		assertThat(addresses.of(request("10.0.0.5", "1.1.1.1", "203.0.113.1"))).isEqualTo("203.0.113.1");
		// Only literals are matched, so no name is looked up.
		assertThat(addresses.of(request("10.0.0.5", "localhost"))).isEqualTo("localhost");
		assertThat(addresses.of(request("10.0.0.5", "172.31.250.9, 10.0.0.5"))).isEqualTo("172.31.250.9");
	}

	@Test
	void readsThePeerBeneathTheForwardedHeaderFilter() {
		var received = request("198.51.100.7", "203.0.113.1");
		var forwarded = new HttpServletRequestWrapper(received) {

			@Override
			public String getRemoteAddr() {
				return "203.0.113.1";
			}

		};
		assertThat(addresses.of(forwarded)).isEqualTo("198.51.100.7");
	}

	@Test
	void trustsNoOneByDefault() {
		assertThat(new ClientAddress(List.of()).of(request("127.0.0.1", "203.0.113.1"))).isEqualTo("127.0.0.1");
	}

	private static MockHttpServletRequest request(String peer, String... forwardedFor) {
		var request = new MockHttpServletRequest("GET", "/products");
		request.setRemoteAddr(peer);
		for (String header : forwardedFor) {
			request.addHeader("X-Forwarded-For", header);
		}
		return request;
	}

}
