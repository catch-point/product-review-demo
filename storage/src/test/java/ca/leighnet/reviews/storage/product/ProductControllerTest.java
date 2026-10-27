package ca.leighnet.reviews.storage.product;

import java.time.Instant;
import java.util.List;

import ca.leighnet.reviews.storage.config.ReviewsProperties;
import ca.leighnet.reviews.storage.config.SecurityConfig;
import ca.leighnet.reviews.storage.jsonld.Hydra;
import ca.leighnet.reviews.storage.jsonld.Iris;
import ca.leighnet.reviews.storage.jsonld.JsonLdContext;
import ca.leighnet.reviews.storage.jsonld.Page;
import ca.leighnet.reviews.storage.person.CurrentUser;
import ca.leighnet.reviews.storage.web.Preconditions;
import ca.leighnet.reviews.storage.web.Versioned;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ETag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@WebMvcTest(controllers = ProductController.class, properties = "reviews.cors.allowed-origins=http://front.example")
@Import({ SecurityConfig.class, Iris.class, Hydra.class })
@EnableConfigurationProperties(ReviewsProperties.class)
class ProductControllerTest {

	@Autowired
	private MockMvcTester mvc;

	@MockitoBean
	private ProductService products;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	// For the rate limiter (web.RateLimiter), which counts every caller here by address.
	@MockitoBean
	private CurrentUser currentUser;

	@Test
	void listsProductsAsAHydraCollection() {
		var aria = new ProductDoc(null, "http://localhost/products/1", "Product", "Aria Headphones", null, null,
				null, null, List.of("Audio"), "Aria", "00012345600012", "ARIA-WH-01", "WH-1000", null, null, null, null,
				"http://localhost/products/1/reviews");
		given(products.list(any(), any())).willReturn(new Page<>(List.of(aria), 3, 1, 1));

		var result = mvc.get().uri("/products?category=Audio&limit=1&offset=1").exchange();

		assertThat(result).hasStatusOk().hasContentType(JsonLdContext.MEDIA_TYPE);
		var body = assertThat(result).bodyJson();
		body.extractingPath("$.@context").isEqualTo("http://localhost/context.jsonld");
		body.extractingPath("$.id").isEqualTo("http://localhost/products?category=Audio");
		body.extractingPath("$.type").isEqualTo("Collection");
		body.extractingPath("$.totalItems").isEqualTo(3);
		body.extractingPath("$.member[0].category[0]").isEqualTo("Audio");
		body.extractingPath("$.view.type").isEqualTo("PartialCollectionView");
		body.extractingPath("$.view.previous").isEqualTo("http://localhost/products?category=Audio&limit=1&offset=0");
		body.extractingPath("$.view.next").isEqualTo("http://localhost/products?category=Audio&limit=1&offset=2");
		body.extractingPath("$.search.template")
			.isEqualTo("http://localhost/products{?category,minRating,maxRating,limit,offset}");
		body.extractingPath("$.search.mapping[0].property").isEqualTo("schema:category");
		body.extractingPath("$.seeAlso").isEqualTo("http://localhost/categories");
	}

	@Test
	void plainJsonIsAlsoOffered() {
		given(products.list(any(), any())).willReturn(new Page<>(List.of(), 0, 20, 0));

		assertThat(mvc.get().uri("/products").accept(MediaType.APPLICATION_JSON))
			.hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
	}

	@Test
	void writesRequireABearerToken() {
		assertThat(mvc.post().uri("/products").contentType(JsonLdContext.MEDIA_TYPE).content("{\"name\":\"X\"}"))
			.hasStatus(HttpStatus.UNAUTHORIZED);
		verifyNoInteractions(products);
	}

	@Test
	void invalidDocumentsAreProblems() {
		var result = mvc.post()
			.uri("/products")
			.with(jwt())
			.contentType(JsonLdContext.MEDIA_TYPE)
			.content("{\"description\":\"no name\"}")
			.exchange();

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
			.hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
		verifyNoInteractions(products);
	}

	@Test
	void createdProductsHaveALocationAndValidators() {
		given(products.create(any())).willReturn(Versioned.of(trattoria(), Instant.ofEpochSecond(1)));

		var result = mvc.post()
			.uri("/products")
			.with(jwt())
			.contentType(JsonLdContext.MEDIA_TYPE)
			.content("{\"name\":\"Aria Headphones\"}")
			.exchange();

		assertThat(result).hasStatus(HttpStatus.CREATED)
			.hasHeader("Location", "http://localhost/products/5")
			.hasHeader("Content-Location", "http://localhost/products/5")
			.hasHeader("ETag", "W/\"f4240\"")
			.hasHeader("Last-Modified", "Thu, 01 Jan 1970 00:00:01 GMT");
	}

	@Test
	void updatesPassOnTheirPreconditionsAndReturnTheNewValidators() {
		var preconditions = ArgumentCaptor.forClass(Preconditions.class);
		given(products.update(eq(5L), any(), preconditions.capture()))
			.willReturn(Versioned.of(trattoria(), Instant.ofEpochSecond(2)));

		var result = mvc.put()
			.uri("/products/5")
			.with(jwt())
			.header("If-Match", "W/\"f4240\", \"other\"")
			.contentType(JsonLdContext.MEDIA_TYPE)
			.content("{\"name\":\"Aria Headphones\"}")
			.exchange();

		assertThat(result).hasStatusOk()
			.hasHeader("Content-Location", "http://localhost/products/5")
			.hasHeader("ETag", "W/\"1e8480\"");
		assertThat(preconditions.getValue().ifMatch()).containsExactly(new ETag("f4240", true),
				new ETag("other", false));
		assertThat(preconditions.getValue().required()).isTrue();
	}

	@Test
	void malformedIfMatchIsABadRequest() {
		var result = mvc.delete().uri("/products/5").with(jwt()).header("If-Match", "not-quoted").exchange();

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		verifyNoInteractions(products);
	}

	@Test
	void browsersMaySendAndReadTheValidators() {
		var preflight = mvc.options()
			.uri("/products/5")
			.header("Origin", "http://front.example")
			.header("Access-Control-Request-Method", "PUT")
			.header("Access-Control-Request-Headers", "if-match")
			.exchange();
		assertThat(preflight).hasStatusOk();
		assertThat(preflight.getResponse().getHeader("Access-Control-Allow-Headers")).containsIgnoringCase("if-match");

		given(products.read(5)).willReturn(Versioned.of(trattoria(), Instant.ofEpochSecond(1)));
		var read = mvc.get().uri("/products/5").header("Origin", "http://front.example").exchange();
		assertThat(read.getResponse().getHeader("Access-Control-Expose-Headers")).contains("ETag", "Last-Modified",
				"Content-Location");
	}

	@Test
	void preflightsAllowOnlyTheMethodsAndHeadersTheApiUses() {
		var allowed = preflight("PUT", "authorization, content-type, if-match, if-unmodified-since");
		assertThat(allowed).hasStatusOk();
		assertThat(allowed.getResponse().getHeader("Access-Control-Allow-Methods")).isEqualTo("GET,HEAD,POST,PUT,DELETE");

		assertThat(preflight("GET", "if-none-match, if-modified-since, accept")).hasStatusOk();
		assertThat(preflight("PATCH", "authorization")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(preflight("PUT", "x-requested-with")).hasStatus(HttpStatus.FORBIDDEN);
	}

	private MvcTestResult preflight(String method, String headers) {
		return mvc.options()
			.uri("/products/5")
			.header("Origin", "http://front.example")
			.header("Access-Control-Request-Method", method)
			.header("Access-Control-Request-Headers", headers)
			.exchange();
	}

	private static ProductDoc trattoria() {
		return new ProductDoc("http://localhost/context.jsonld", "http://localhost/products/5", "Product",
				"Aria Headphones", null, null, null, null, null, null, null, null, null, null, null, null, null, null);
	}

}
