package ca.leighnet.reviews.storage.jsonld;

import java.util.List;

import ca.leighnet.reviews.storage.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

class IrisTest {

	private final Iris iris = new Iris();

	@BeforeEach
	void bindRequest() {
		var request = new MockHttpServletRequest("GET", "/products");
		request.setScheme("https");
		request.setServerName("reviews.example");
		request.setServerPort(443);
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
	}

	@AfterEach
	void unbindRequest() {
		RequestContextHolder.resetRequestAttributes();
	}

	@Test
	void namesResourcesByTheirUrl() {
		assertThat(iris.of(Iris.PRODUCTS, 7)).isEqualTo("https://reviews.example/products/7");
		assertThat(iris.context()).isEqualTo("https://reviews.example/context.jsonld");
	}

	@Test
	void parsesAbsoluteAndRelativeIris() {
		assertThat(iris.id(Iris.PRODUCTS, "https://reviews.example/products/7", "sameAs")).isEqualTo(7);
		assertThat(iris.id(Iris.PRODUCTS, "/products/8", "sameAs")).isEqualTo(8);
	}

	@Test
	void rejectsIrisOfOtherResourcesOrServers() {
		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> iris.id(Iris.PRODUCTS, "https://reviews.example/people/7", "sameAs"))
			.withMessageContaining("sameAs");
		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> iris.id(Iris.PRODUCTS, "https://elsewhere.example/products/7", "sameAs"));
		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> iris.id(Iris.PRODUCTS, "/products/seven", "sameAs"));
	}

	@Test
	void acceptsOnlyThisServersContext() {
		assertThatNoException().isThrownBy(() -> iris.checkContext(null));
		assertThatNoException().isThrownBy(() -> iris.checkContext("https://reviews.example/context.jsonld"));
		assertThatNoException().isThrownBy(() -> iris.checkContext(List.of("https://reviews.example/context.jsonld")));
		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> iris.checkContext("https://schema.org/"));
	}

	@Test
	void requiresAbsoluteHttpUrls() {
		assertThat(Iris.absoluteUri("https://example.com/menu", "url")).isEqualTo("https://example.com/menu");
		assertThat(Iris.absoluteUri(" ", "url")).isNull();
		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> Iris.absoluteUri("menu.html", "url"));
		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> Iris.absoluteUri("javascript:alert(1)", "url"))
			.withMessageContaining("http or https");
		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> Iris.absoluteUri("data:text/html,hi", "url"));
	}

	@Test
	void storesImagesByTheirPathFromTheRoot() {
		String name = "a".repeat(64) + ".png";
		assertThat(iris.imagePath("https://reviews.example/images/" + name, "image")).isEqualTo("/images/" + name);
		assertThat(iris.imagePath("/images/" + name, "image")).isEqualTo("/images/" + name);
		assertThat(iris.imagePath(" ", "image")).isNull();
		assertThat(iris.image("/images/" + name)).isEqualTo("https://reviews.example/images/" + name);
		assertThat(iris.image(null)).isNull();
	}

	@Test
	void storesImagesWithoutTheProxysPrefix() {
		var request = new MockHttpServletRequest("GET", "/api/storage/products");
		request.setContextPath("/api/storage");
		request.setServerName("localhost");
		request.setServerPort(3000);
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
		String name = "b".repeat(64) + ".jpg";
		assertThat(iris.imagePath("http://localhost:3000/api/storage/images/" + name, "image"))
			.isEqualTo("/images/" + name);
		assertThat(iris.image("/images/" + name)).isEqualTo("http://localhost:3000/api/storage/images/" + name);
	}

	@Test
	void refusesImagesThatAreNotUploadsOfThisServer() {
		String name = "a".repeat(64) + ".png";
		for (String other : List.of("https://elsewhere.example/images/" + name, "https://example.com/dish.png",
				"/images/dish.png", "/images/../" + name, "/images/" + name + "?x", "dish.png",
				"javascript:alert(1)", "data:image/png;base64,AAAA")) {
			assertThatExceptionOfType(ApiException.class).as(other)
				.isThrownBy(() -> iris.imagePath(other, "image"))
				.withMessageContaining("POST /images");
		}
	}

}
