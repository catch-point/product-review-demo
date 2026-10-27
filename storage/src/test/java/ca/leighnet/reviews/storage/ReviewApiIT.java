package ca.leighnet.reviews.storage;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

import com.apicatalog.jsonld.JsonLd;
import com.apicatalog.jsonld.document.Document;
import com.apicatalog.jsonld.document.JsonDocument;
import com.apicatalog.jsonld.loader.DocumentLoader;
import ca.leighnet.reviews.storage.cache.Caches;
import ca.leighnet.reviews.storage.image.TestImages;
import ca.leighnet.reviews.storage.jsonld.JsonLdContext;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * The whole service against a real PostgreSQL (schema.sql) container.
 */
@SpringBootTest(properties = { "reviews.security.jwt-secret=test-secret-0123456789abcdef0123456789abcdef",
		// Rate limits are tested in RequestLimitsIT; these tests read more than a client may.
		"reviews.rate-limits.enabled=false",
		"reviews.images.max-size=64KB" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewApiIT {

	private static final String ADMIN = "ada@example.com";

	private static final MediaType LD_JSON = JsonLdContext.MEDIA_TYPE;

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private JsonMapper json;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private CacheManager cacheManager;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	@BeforeAll
	void registerAdministrator() {
		register(ADMIN, "Ada");
	}

	@Test
	void servesTheContextAndCategories() {
		assertThat(mvc.get().uri("/context.jsonld")).hasStatusOk().hasContentType(LD_JSON);

		var categories = body(mvc.get().uri("/categories").exchange());
		assertThat(categories.path("type").asString()).isEqualTo("CategoryList");
		assertThat(categories.path("hasDefinedTerm").values().stream().map(JsonNode::asString))
			.contains("Audio", "Books", "Electronics", "Home", "Outdoors", "Toys");
	}

	@Test
	void registersPeopleAndShowsEmailOnlyToThemAtPeopleMe() {
		String email = email("olive");
		var olive = register(email, "Olive");
		String path = path(olive.path("id").asString());

		assertThat(olive.has("email")).as("201 is the Person as /people/{id} shows it").isFalse();
		assertThat(post(email, "/people", "{\"name\":\"Again\"}")).hasStatus(HttpStatus.CONFLICT);
		// The same for everyone, the person and administrators included.
		for (var caller : Arrays.asList(null, email("rob"), ADMIN, email)) {
			var request = mvc.get().uri(path);
			assertThat(body((caller != null) ? request.with(as(caller)).exchange() : request.exchange()).has("email"))
				.as("email shown to %s", caller)
				.isFalse();
		}
		var me = body(mvc.get().uri("/people/me").with(as(email)).exchange());
		assertThat(me.path("id").asString()).isEqualTo(olive.path("id").asString());
		assertThat(me.path("email").asString()).isEqualTo(email);
	}

	@Test
	void requiresAuthenticationAndRegistration() {
		assertThat(mvc.post().uri("/products").contentType(LD_JSON).content(product("Anon")))
			.hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(post(email("owner-stranger"), "/products", product("Unregistered")))
			.hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void onlyOwnersAddProductsAndOnlyReviewersReview() {
		String owner = email("owner");
		String otherOwner = email("other");
		String rob = email("rob");
		String roleless = email("roleless");
		register(owner, "Owner");
		register(otherOwner, "Other");
		register(rob, "Rob");
		register(roleless, "Roleless");

		assertThat(post(rob, "/products", product("Rob's"))).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(mvc.post().uri("/products").with(as(roleless, null)).contentType(LD_JSON)
			.content(product("Nobody's"))).hasStatus(HttpStatus.FORBIDDEN);
		String product = path(body(post(owner, "/products", product("Owned")), HttpStatus.CREATED)
			.path("id")
			.asString());

		assertThat(post(otherOwner, product + "/reviews", review(1, "Competitor"))).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(mvc.post().uri(product + "/reviews").with(as(roleless, null)).contentType(LD_JSON)
			.content(review(1, "Who am I"))).hasStatus(HttpStatus.FORBIDDEN);
		body(post(rob, product + "/reviews", review(5, "Great")), HttpStatus.CREATED);
	}

	@Test
	void ownersManageTheirProducts() {
		String owner = email("owner");
		String other = email("other");
		var person = register(owner, "Owner");
		register(other, "Other");

		String category = newCategory();
		var created = body(post(owner, "/products", product("Aria", category)), HttpStatus.CREATED);
		String path = path(created.path("id").asString());
		assertThat(created.path("owner").asString()).isEqualTo(person.path("id").asString());
		assertThat(created.path("category").get(0).asString()).isEqualTo(category);
		assertThat(created.path("brand").asString()).isEqualTo("Aria");
		assertThat(created.path("gtin").asString()).isEqualTo("00012345600012");
		assertThat(created.path("sku").asString()).isEqualTo("ARIA-100");
		assertThat(created.path("mpn").asString()).isEqualTo("ARIA-100-BLK");

		assertThat(put(other, path, product("Stolen", category))).hasStatus(HttpStatus.FORBIDDEN);
		var updated = body(put(owner, path, product("Aria II", category)), HttpStatus.OK);
		assertThat(updated.path("name").asString()).isEqualTo("Aria II");
		assertThat(updated.has("dateModified")).isTrue();

		assertThat(listIds("/products?category={category}", category)).contains(created.path("id").asString());
		assertThat(listIds("/products?category={category}", "https://example.com/categories/home"))
			.doesNotContain(created.path("id").asString());
		assertThat(mvc.get().uri("/products?category=unknown")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(body(mvc.get().uri(path).exchange()).path("@context").asString()).endsWith("/context.jsonld");
	}

	@Test
	void reviewsMaintainTheAggregateRating() {
		String owner = email("owner");
		String rob = email("rob");
		String eve = email("eve");
		register(owner, "Owner");
		register(rob, "Rob");
		register(eve, "Eve");
		String product = path(body(post(owner, "/products", product("Bistro")), HttpStatus.CREATED)
			.path("id")
			.asString());

		assertThat(post(owner, product + "/reviews", review(5, "Mine is best"))).hasStatus(HttpStatus.FORBIDDEN);
		var robs = body(post(rob, product + "/reviews", review(4, "Lovely")), HttpStatus.CREATED);
		// 2 on a 0-10 scale is 1.8 on the 1-5 scale.
		body(post(eve, product + "/reviews",
				"{\"reviewRating\":{\"type\":\"Rating\",\"ratingValue\":2,\"worstRating\":0,\"bestRating\":10}}"),
				HttpStatus.CREATED);
		assertAggregate(product, "2.9", 2, 1);

		String review = path(robs.path("id").asString());
		// Only the product's owner replies: not other reviewers, the review's author or other owners.
		String other = email("other");
		register(other, "Other owner");
		for (String someoneElse : List.of(eve, rob, other)) {
			assertThat(post(someoneElse, review + "/comments", "{\"text\":\"I disagree\"}"))
				.hasStatus(HttpStatus.FORBIDDEN);
		}
		var reply = body(post(owner, review + "/comments", "{\"type\":\"Comment\",\"text\":\"Thank you!\"}"),
				HttpStatus.CREATED);
		// Once: a second reply is refused, naming the first, which they change instead.
		assertThat(post(owner, review + "/comments", "{\"text\":\"Thanks again!\"}")).hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.comment")
			.isEqualTo(reply.path("id").asString());

		body(put(rob, review, review(3, "Still lovely")), HttpStatus.OK);
		assertAggregate(product, "2.4", 2, 1);

		var listed = body(mvc.get().uri(product + "/reviews").exchange()).path("member");
		assertThat(listed.size()).isEqualTo(2);
		var robsListed = listed.values().stream().filter(r -> r.path("id").equals(robs.path("id"))).findFirst();
		assertThat(robsListed).get()
			.satisfies(r -> assertThat(r.path("comment").get(0).path("id")).isEqualTo(reply.path("id")))
			.satisfies(r -> assertThat(r.path("author").path("name").asString()).isEqualTo("Rob"))
			.satisfies(r -> assertThat(r.path("comment").get(0).path("author").path("name").asString())
				.isEqualTo("Owner"));
		assertThat(body(mvc.get().uri(review).exchange()).path("comment").values().stream()
			.map(c -> c.path("text").asString())).containsExactly("Thank you!");

		assertThat(delete(owner, review)).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(delete(rob, review)).hasStatus(HttpStatus.NO_CONTENT);
		assertAggregate(product, "1.8", 1, 0);
		assertThat(mvc.get().uri(path(reply.path("id").asString()))).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void rejectsInvalidDocuments() {
		String owner = email("owner");
		String rob = email("rob");
		register(owner, "Owner");
		register(rob, "Rob");
		String product = path(body(post(owner, "/products", product("Taverna")), HttpStatus.CREATED)
			.path("id")
			.asString());

		assertThat(post(owner, "/products", "{\"@context\":\"https://schema.org/\",\"name\":\"Elsewhere\"}"))
			.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(post(owner, "/products", "{\"type\":\"Review\",\"name\":\"Confused\"}"))
			.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(post(owner, "/products", "{\"description\":\"No name\"}")).hasStatus(HttpStatus.BAD_REQUEST);
		var problem = post(rob, product + "/reviews", review(6, "Off the scale"));
		assertThat(problem).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("between 1 and 5");
	}

	@Test
	void documentsExpandToSchemaOrg() throws Exception {
		String owner = email("owner");
		String rob = email("rob");
		var person = register(owner, "Olive");
		var reviewer = register(rob, "Rob");
		var product = body(post(owner, "/products", product("Aria One")), HttpStatus.CREATED);
		var review = body(post(rob, path(product.path("id").asString()) + "/reviews", review(5, "Superb")),
				HttpStatus.CREATED);

		JsonObject r = expand(body(mvc.get().uri(path(product.path("id").asString())).exchange()));
		assertThat(r.getJsonArray("@type").getString(0)).isEqualTo("https://schema.org/Product");
		assertThat(id(r, "https://schema.org/category")).isEqualTo("https://example.com/categories/audio");
		assertThat(id(r, "https://schema.org/owner")).isEqualTo(person.path("id").asString());
		assertThat(value(r, "https://schema.org/name")).isEqualTo("Aria One");
		assertThat(value(r, "https://schema.org/brand")).isEqualTo("Aria");
		assertThat(value(r, "https://schema.org/gtin")).isEqualTo("00012345600012");
		assertThat(value(r, "https://schema.org/sku")).isEqualTo("ARIA-100");
		assertThat(value(r, "https://schema.org/mpn")).isEqualTo("ARIA-100-BLK");
		JsonObject aggregate = r.getJsonArray("https://schema.org/aggregateRating").getJsonObject(0);
		assertThat(aggregate.getJsonArray("https://schema.org/ratingCount")
			.getJsonObject(0)
			.getString("@type")).isEqualTo("http://www.w3.org/2001/XMLSchema#integer");

		JsonObject v = expand(body(mvc.get().uri(path(review.path("id").asString())).exchange()));
		assertThat(id(v, "https://schema.org/itemReviewed")).isEqualTo(product.path("id").asString());
		JsonObject author = v.getJsonArray("https://schema.org/author").getJsonObject(0);
		assertThat(author.getString("@id")).isEqualTo(reviewer.path("id").asString());
		assertThat(author.getJsonArray("@type").getString(0)).isEqualTo("https://schema.org/Person");
		assertThat(value(author, "https://schema.org/name")).isEqualTo("Rob");
		assertThat(author.containsKey("https://schema.org/email")).isFalse();
		JsonObject rating = v.getJsonArray("https://schema.org/reviewRating").getJsonObject(0);
		assertThat(rating.getJsonArray("https://schema.org/ratingValue").getJsonObject(0).getString("@type"))
			.isEqualTo("http://www.w3.org/2001/XMLSchema#decimal");

		JsonObject p = expand(body(mvc.get().uri(path(person.path("id").asString())).exchange()));
		assertThat(value(p, "https://schema.org/name")).isEqualTo("Olive");
		assertThat(id(p, "https://schema.org/owns")).isEqualTo(product.path("id").asString());
	}

	@Test
	void journalRecordsWhoChangedWhat() {
		String owner = email("owner");
		var person = register(owner, "Owner");
		long personId = Long.parseLong(person.path("id").asString().replaceAll(".*/", ""));
		body(post(owner, "/products", product("Journaled")), HttpStatus.CREATED);

		String actor = "person:" + personId;
		var products = jdbc.sql("""
				SELECT op || ' ' || (new_row->>'name') FROM journal
				WHERE table_name = 'product' AND actor = :actor
				""").param("actor", actor).query(String.class).list();
		assertThat(products).containsExactly("INSERT Journaled");
		var registration = jdbc.sql("""
				SELECT (pk->>'person_id')::bigint FROM journal
				WHERE table_name = 'person' AND op = 'INSERT' AND actor = :actor
				""").param("actor", actor).query(Long.class).single();
		assertThat(registration).isEqualTo(personId);
	}

	@Test
	void unchangedUpdatesAreNotJournaled() {
		String owner = email("owner");
		var person = register(owner, "Owner");
		String path = path(body(post(owner, "/products", product("Same")), HttpStatus.CREATED).path("id").asString());
		var same = body(mvc.get().uri(path).exchange());

		var again = body(put(owner, path, json.writeValueAsString(same)), HttpStatus.OK);

		assertThat(again.has("dateModified")).isFalse();
		var updates = jdbc.sql("SELECT count(*) FROM journal WHERE table_name = 'product' AND op = 'UPDATE' AND actor = :actor")
			.param("actor", "person:" + person.path("id").asString().replaceAll(".*/", ""))
			.query(Long.class)
			.single();
		assertThat(updates).isZero();
	}

	@Test
	void servesExactlyTheOperationsInTheOpenApiDocument() throws Exception {
		var result = mvc.get().uri("/openapi.yml").exchange();
		assertThat(result).hasStatusOk();
		Map<String, Map<String, Object>> paths = new Yaml()
			.<Map<String, Map<String, Map<String, Object>>>>load(result.getResponse().getContentAsString())
			.get("paths");
		Set<String> documented = new TreeSet<>();
		paths.forEach((path, operations) -> operations.keySet()
			.stream()
			.filter(method -> Set.of("get", "put", "post", "delete", "patch").contains(method))
			.forEach(method -> documented.add(method.toUpperCase(Locale.ROOT) + " " + normalize(path))));

		Set<String> served = new TreeSet<>();
		handlerMapping.getHandlerMethods().forEach((info, handler) -> {
			if (handler.getBeanType().getPackageName().startsWith("ca.leighnet.reviews")) {
				info.getPatternValues()
					.forEach(path -> info.getMethodsCondition()
						.getMethods()
						.forEach(method -> served.add(method.name() + " " + normalize(path))));
			}
		});

		assertThat(served).containsExactlyElementsOf(documented);
	}

	private static String normalize(String path) {
		return path.replaceAll("\\{[^}]+}", "{}");
	}

	@Test
	void collectionsPageWithHydraLinks() {
		String owner = email("owner");
		register(owner, "Owner");
		String category = newCategory();
		for (String name : List.of("Alpha", "Bravo", "Charlie")) {
			body(post(owner, "/products", product(name, category)), HttpStatus.CREATED);
		}

		var first = body(mvc.get().uri("/products?category={category}&limit=2", category).exchange());
		var categories = body(mvc.get().uri(path(first.path("seeAlso").asString())).exchange());
		assertThat(categories.path("type").asString()).isEqualTo("CategoryList");
		assertThat(first.path("type").asString()).isEqualTo("Collection");
		assertThat(first.path("id").asString()).startsWith("http://localhost/products?category=").contains(category);
		assertThat(first.path("totalItems").asInt()).isEqualTo(3);
		assertThat(names(first)).containsExactly("Alpha", "Bravo");
		var view = first.path("view");
		assertThat(view.path("type").asString()).isEqualTo("PartialCollectionView");
		assertThat(view.has("previous")).isFalse();
		assertThat(view.path("last").asString()).isEqualTo(view.path("next").asString());
		assertThat(first.path("search").path("type").asString()).isEqualTo("IriTemplate");

		var second = body(mvc.get().uri(path(view.path("next").asString())).exchange());
		assertThat(names(second)).containsExactly("Charlie");
		assertThat(second.path("view").has("next")).isFalse();
		var back = body(mvc.get().uri(path(second.path("view").path("previous").asString())).exchange());
		assertThat(names(back)).containsExactly("Alpha", "Bravo");
	}

	@Test
	void reviewersReviewEachProductOnceThenUpdateIt() {
		String owner = email("owner");
		String rob = email("rob");
		String eve = email("eve");
		register(owner, "Owner");
		String robId = register(rob, "Rob").path("id").asString();
		String eveId = register(eve, "Eve").path("id").asString();
		String product = path(body(post(owner, "/products", product("Once")), HttpStatus.CREATED)
			.path("id")
			.asString());

		var robs = body(post(rob, product + "/reviews", review(4, "Good")), HttpStatus.CREATED);
		var again = post(rob, product + "/reviews", review(1, "Again"));
		assertThat(again).hasStatus(HttpStatus.CONFLICT).bodyJson().satisfies(problem -> {
			assertThat(problem).extractingPath("$.review").isEqualTo(robs.path("id").asString());
			assertThat(problem).extractingPath("$.detail").asString().contains("already reviewed");
		});
		assertAggregate(product, "4.0", 1, 1);

		// Each reviewer finds their own review of it, to update it.
		var mine = body(mvc.get().uri(product + "/reviews?author={author}", robId).exchange());
		assertThat(mine.path("totalItems").asInt()).isEqualTo(1);
		assertThat(mine.path("member").get(0).path("id")).isEqualTo(robs.path("id"));
		assertThat(mine.path("search").path("template").asString()).endsWith(product + "/reviews{?author,limit,offset}");
		assertThat(body(mvc.get().uri(product + "/reviews?author={author}", eveId).exchange()).path("totalItems")
			.asInt()).isZero();
		assertThat(mvc.get().uri(product + "/reviews?author={author}", product)).hasStatus(HttpStatus.BAD_REQUEST);

		body(put(rob, path(robs.path("id").asString()), review(2, "Not so good")), HttpStatus.OK);
		assertAggregate(product, "2.0", 1, 1);
		// Once it is deleted, they may review it again.
		assertThat(delete(rob, path(robs.path("id").asString()))).hasStatus(HttpStatus.NO_CONTENT);
		body(post(rob, product + "/reviews", review(5, "Back again")), HttpStatus.CREATED);
		assertAggregate(product, "5.0", 1, 1);
	}

	@Test
	void discontinuedProductsLeaveListingsAndTakeNoNewReviews() throws Exception {
		String owner = email("owner");
		String rob = email("rob");
		String eve = email("eve");
		register(owner, "Owner");
		register(rob, "Rob");
		register(eve, "Eve");
		String category = newCategory();
		String open = path(body(post(owner, "/products", product("Open", category)), HttpStatus.CREATED)
			.path("id")
			.asString());
		String closed = path(body(post(owner, "/products", product("Closed", category)), HttpStatus.CREATED)
			.path("id")
			.asString());
		String robs = path(body(post(rob, closed + "/reviews", review(4, "Before it closed")), HttpStatus.CREATED)
			.path("id")
			.asString());

		assertThat(delete(owner, closed)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(body(mvc.get().uri(open).exchange()).has("discontinuedDate")).isFalse();

		// A repeated delete by the owner succeeds without a precondition.
		assertThat(mvc.delete().uri(closed).with(as(owner))).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(mvc.delete().uri(closed).with(as(rob))).hasStatus(HttpStatus.NOT_FOUND);

		// Discontinued products leave every collection and direct read.
		var listed = body(mvc.get().uri("/products?category={category}", category).exchange());
		assertThat(names(listed)).containsExactly("Open");
		assertThat(listed.path("totalItems").asInt()).isEqualTo(1);
		assertThat(names(body(mvc.get().uri("/products?category={category}&minRating=1", category).exchange())))
			.isEmpty();
		assertThat(mvc.get().uri(closed)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.get().uri(closed + "/reviews")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(put(owner, closed, product("Reopened", category))).hasStatus(HttpStatus.NOT_FOUND);

		// Its reviews stay readable and editable, and its owner may still reply.
		assertThat(mvc.get().uri(robs)).hasStatusOk();
		body(put(rob, robs, review(3, "Before it closed, on reflection")), HttpStatus.OK);
		body(post(owner, robs + "/comments", "{\"text\":\"Thank you for coming\"}"), HttpStatus.CREATED);

		// It takes no new reviews.
		assertThat(post(eve, closed + "/reviews", review(5, "Too late"))).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void productsFilterByARangeOfRatings() {
		String owner = email("owner");
		String rob = email("rob");
		register(owner, "Owner");
		register(rob, "Rob");
		String category = newCategory();
		for (int rating : List.of(2, 4, 5)) {
			String product = path(body(post(owner, "/products", product("Rated " + rating, category)),
					HttpStatus.CREATED)
				.path("id")
				.asString());
			body(post(rob, product + "/reviews", review(rating, "Rated")), HttpStatus.CREATED);
		}
		body(post(owner, "/products", product("Unrated", category)), HttpStatus.CREATED);

		assertThat(names(body(mvc.get().uri("/products?category={category}&maxRating=4", category).exchange())))
			.containsExactly("Rated 4", "Rated 2");
		var range = body(mvc.get().uri("/products?category={category}&minRating=3&maxRating=4.5", category).exchange());
		assertThat(names(range)).containsExactly("Rated 4");
		assertThat(range.path("totalItems").asInt()).isEqualTo(1);
		assertThat(body(mvc.get().uri("/products?category={category}", category).exchange()).path("totalItems")
			.asInt()).isEqualTo(4);
	}

	@Test
	void listsPeopleAsACollection() {
		String owner = email("owner");
		String rob = email("rob");
		var olive = register(owner, "Olive");
		register(rob, "Rob");
		String product = path(body(post(owner, "/products", product("Listed")), HttpStatus.CREATED)
			.path("id")
			.asString());

		var people = body(mvc.get().uri("/people?limit=100").with(as(owner)).exchange());
		assertThat(people.path("totalItems").asInt()).isGreaterThanOrEqualTo(2);
		var listedOlive = people.path("member")
			.values()
			.stream()
			.filter(p -> p.path("id").equals(olive.path("id")))
			.findFirst();
		assertThat(listedOlive).get().satisfies(p -> {
			assertThat(p.has("email")).isFalse();
			assertThat(p.path("owns").get(0).asString()).endsWith(product);
		});
	}

	@Test
	void collectionsExpandToHydra() throws Exception {
		String owner = email("owner");
		register(owner, "Owner");
		String category = newCategory();
		var created = body(post(owner, "/products", product("Expanded", category)), HttpStatus.CREATED);
		body(post(owner, "/products", product("Zeta", category)), HttpStatus.CREATED);

		String hydra = "http://www.w3.org/ns/hydra/core#";
		JsonObject c = expand(body(mvc.get().uri("/products?category={category}&limit=1", category).exchange()));
		assertThat(c.getJsonArray("@type").getString(0)).isEqualTo(hydra + "Collection");
		JsonObject total = c.getJsonArray(hydra + "totalItems").getJsonObject(0);
		assertThat(total.getString("@type")).isEqualTo("http://www.w3.org/2001/XMLSchema#nonNegativeInteger");
		JsonObject member = c.getJsonArray(hydra + "member").getJsonObject(0);
		assertThat(member.getString("@id")).isEqualTo(created.path("id").asString());
		assertThat(member.getJsonArray("@type").getString(0)).isEqualTo("https://schema.org/Product");
		assertThat(value(member, "https://schema.org/name")).isEqualTo("Expanded");
		JsonObject view = c.getJsonArray(hydra + "view").getJsonObject(0);
		assertThat(view.getJsonArray("@type").getString(0)).isEqualTo(hydra + "PartialCollectionView");
		assertThat(id(view, hydra + "next")).endsWith("offset=1");
		assertThat(id(c, "http://www.w3.org/2000/01/rdf-schema#seeAlso")).isEqualTo("http://localhost/categories");
		JsonObject search = c.getJsonArray(hydra + "search").getJsonObject(0);
		assertThat(search.getJsonArray(hydra + "template").getJsonObject(0).getString("@type"))
			.isEqualTo(hydra + "Rfc6570Template");
		assertThat(search.getJsonArray(hydra + "mapping")
			.getValuesAs(JsonObject.class)
			.stream()
			.map(m -> id(m, hydra + "property"))).contains("https://schema.org/category", hydra + "limit");
	}

	private static List<String> names(JsonNode collection) {
		return collection.path("member").values().stream().map(r -> r.path("name").asString()).toList();
	}

	@Test
	void uploadsAndServesImages() throws Exception {
		String owner = email("owner");
		register(owner, "Owner");
		byte[] png = TestImages.encode("png", 30, 20);

		var created = upload(owner, "photo.jpg", "image/jpeg", png);
		assertThat(created).hasStatus(HttpStatus.CREATED);
		var image = json.readTree(created.getResponse().getContentAsByteArray());
		assertThat(created.getResponse().getHeader("Location")).isEqualTo(image.path("id").asString());
		assertThat(image.path("type").asString()).isEqualTo("ImageObject");
		assertThat(image.path("encodingFormat").asString()).isEqualTo("image/png");
		assertThat(image.path("width").asInt()).isEqualTo(30);
		assertThat(image.path("height").asInt()).isEqualTo(20);
		String path = path(image.path("id").asString());

		var served = mvc.get().uri(path).exchange();
		assertThat(served).hasStatusOk()
			.hasContentType("image/png")
			.hasHeader("ETag", "\"" + image.path("sha256").asString() + "\"")
			.hasHeader("X-Content-Type-Options", "nosniff");
		assertThat(served.getResponse().getHeader("Cache-Control")).contains("immutable");
		assertThat(served.getResponse().getContentAsByteArray()).isEqualTo(png);
		assertThat(mvc.get().uri(path).header("If-None-Match", served.getResponse().getHeader("ETag")))
			.hasStatus(HttpStatus.NOT_MODIFIED);
		assertThat(mvc.get().uri(path).header("If-None-Match", "*")).hasStatus(HttpStatus.NOT_MODIFIED);
		assertThat(mvc.get().uri("/images/{id}", "0".repeat(64) + ".png").header("If-None-Match", "*"))
			.hasStatus(HttpStatus.NOT_FOUND);

		var again = upload(owner, "copy.png", "image/png", png);
		assertThat(again).hasStatus(HttpStatus.OK);
		assertThat(json.readTree(again.getResponse().getContentAsByteArray()).path("id")).isEqualTo(image.path("id"));
	}

	@Test
	void rejectsUploadsThatAreNotSmallImages() {
		String owner = email("owner");
		register(owner, "Owner");

		assertThat(upload(owner, "evil.png", "image/png", "<script>alert(1)</script>".getBytes()))
			.hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		byte[] large = TestImages.noise("png", 200, 200);
		assertThat(large.length).isGreaterThan(64 * 1024);
		assertThat(upload(owner, "large.png", "image/png", large)).hasStatus(HttpStatus.CONTENT_TOO_LARGE);
		byte[] png = TestImages.encode("png", 1, 1);
		assertThat(mvc.post().uri("/images").multipart().file(new MockMultipartFile("file", "a.png", "image/png", png)))
			.hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(upload(email("stranger"), "a.png", "image/png", png)).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(mvc.get().uri("/images/{id}", "0".repeat(64) + ".png")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.get().uri("/images/..%2F..%2Fetc%2Fpasswd")).hasStatus4xxClientError();
	}

	@Test
	void boundsRequestBodies() throws Exception {
		String owner = email("owner");
		register(owner, "Owner");
		String padded = product("Padded").replaceFirst("\\{", "{\"padding\": \"" + "x".repeat(64 * 1024) + "\",");

		// Refused by its Content-Length, unread and before authentication.
		for (var request : List.of(mvc.post().uri("/products"),
				mvc.post().uri("/products").header("X-Forwarded-Prefix", "/api/storage"),
				mvc.post().uri("/products").with(as(owner)))) {
			var refused = request.contentType(LD_JSON).content(padded).exchange();
			assertThat(refused).hasStatus(HttpStatus.CONTENT_TOO_LARGE).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
			assertThat(json.readTree(refused.getResponse().getContentAsByteArray()).path("detail").asString())
				.isEqualTo("Request bodies may be at most 64 KB");
		}

		// Within the body limit, but more than any field or document may hold.
		String product = product("Bounded");
		for (String tooMuch : List.of(
				product.replace("\"Noise-cancelling headphones.\"", "\"" + "x".repeat(30_000) + "\""),
				product.replace("\"Noise-cancelling headphones.\"", "\"" + "x".repeat(5_001) + "\""),
				product.replace("\"https://products.example/", "\"https://products.example/" + "x".repeat(2_000)),
				product.replace("[\"https://example.com/categories/audio\"]",
						"[" + String.join(",", Collections.nCopies(21, "\"https://example.com/categories/audio\"")) + "]"),
				product.replace("\"http://localhost/context.jsonld\"", "[".repeat(40) + "]".repeat(40)),
				product.replace("\"type\": \"Product\"", "\"type\": \"" + "R".repeat(101) + "\""))) {
			assertThat(post(owner, "/products", tooMuch)).hasStatus(HttpStatus.BAD_REQUEST);
		}
		assertThat(post(owner, "/products", product)).hasStatus(HttpStatus.CREATED);
	}

	@Test
	void reviewsCarryAnImage() throws Exception {
		String owner = email("owner");
		String rob = email("rob");
		register(owner, "Owner");
		register(rob, "Rob");
		String product = path(body(post(owner, "/products", product("Pictured")), HttpStatus.CREATED)
			.path("id")
			.asString());
		var image = json.readTree(upload(rob, "dish.png", "image/png", TestImages.encode("png", 4, 3)).getResponse()
			.getContentAsByteArray());
		String imageUrl = image.path("id").asString();

		var review = body(post(rob, product + "/reviews",
				"{\"image\":\"" + imageUrl + "\",\"reviewRating\":{\"ratingValue\":5}}"), HttpStatus.CREATED);
		assertThat(review.path("image").asString()).isEqualTo(imageUrl);
		// Stored by its path from the root, whatever address it was given at.
		assertThat(jdbc.sql("SELECT image FROM review WHERE review_id = :id")
			.param("id", Long.parseLong(review.path("id").asString().replaceAll(".*/", "")))
			.query(String.class)
			.single()).isEqualTo("/images/" + image.path("sha256").asString() + ".png");
		// Checked before the review is refused as a second one: only this service's uploads.
		for (String other : List.of("dish.png", "https://example.com/dish.png")) {
			assertThat(post(rob, product + "/reviews",
					"{\"image\":\"" + other + "\",\"reviewRating\":{\"ratingValue\":5}}"))
				.hasStatus(HttpStatus.BAD_REQUEST);
		}

		JsonObject v = expand(body(mvc.get().uri(path(review.path("id").asString())).exchange()));
		assertThat(id(v, "https://schema.org/image")).isEqualTo(imageUrl);
		JsonObject i = expand(image);
		assertThat(i.getJsonArray("@type").getString(0)).isEqualTo("https://schema.org/ImageObject");
		assertThat(id(i, "https://schema.org/contentUrl")).isEqualTo(imageUrl);
		assertThat(i.getJsonArray("https://schema.org/width").getJsonObject(0).getString("@type"))
			.isEqualTo("http://www.w3.org/2001/XMLSchema#integer");
		assertThat(value(i, "https://schema.org/encodingFormat")).isEqualTo("image/png");
	}

	private MvcTestResult upload(String email, String filename, String contentType, byte[] bytes) {
		return mvc.post()
			.uri("/images")
			.multipart()
			.file(new MockMultipartFile("file", filename, contentType, bytes))
			.with(as(email))
			.exchange();
	}

	@Test
	void seeAlsoLinksToTheReviews() throws Exception {
		String owner = email("owner");
		String rob = email("rob");
		register(owner, "Owner");
		register(rob, "Rob");
		var product = body(post(owner, "/products", product("Linked")), HttpStatus.CREATED);
		String productId = product.path("id").asString();
		var review = body(post(rob, path(productId) + "/reviews", review(4, "Linked up")), HttpStatus.CREATED);
		String reviewId = review.path("id").asString();
		body(post(owner, path(reviewId) + "/comments", "{\"text\":\"Thanks\"}"), HttpStatus.CREATED);

		var fetchedProduct = body(mvc.get().uri(path(productId)).exchange());
		assertThat(fetchedProduct.path("seeAlso").asString()).isEqualTo(productId + "/reviews");
		var reviews = body(mvc.get().uri(path(fetchedProduct.path("seeAlso").asString())).exchange());
		assertThat(reviews.path("type").asString()).isEqualTo("Collection");
		assertThat(reviews.path("member").get(0).path("id").asString()).isEqualTo(reviewId);

		// A review embeds its reply, and links nowhere else.
		var fetchedReview = body(mvc.get().uri(path(reviewId)).exchange());
		assertThat(fetchedReview.has("seeAlso")).isFalse();
		assertThat(reviews.path("member").get(0).has("seeAlso")).isFalse();

		String rdfsSeeAlso = "http://www.w3.org/2000/01/rdf-schema#seeAlso";
		assertThat(id(expand(fetchedProduct), rdfsSeeAlso)).isEqualTo(productId + "/reviews");
		assertThat(post(owner, "/products", "{\"name\":\"Ignored link\",\"seeAlso\":\"https://elsewhere.example\"}"))
			.hasStatus(HttpStatus.CREATED)
			.bodyJson()
			.extractingPath("$.seeAlso")
			.asString()
			.endsWith("/reviews");
	}

	@Test
	void reviewsEmbedTheOwnersReply() {
		String owner = email("owner");
		String rob = email("rob");
		String eve = email("eve");
		register(owner, "Owner");
		register(rob, "Rob");
		register(eve, "Eve");
		String product = path(body(post(owner, "/products", product("Chatty")), HttpStatus.CREATED)
			.path("id")
			.asString());
		String answered = path(body(post(rob, product + "/reviews", review(4, "Answered")), HttpStatus.CREATED)
			.path("id")
			.asString());
		String unanswered = path(body(post(eve, product + "/reviews", review(3, "Unanswered")), HttpStatus.CREATED)
			.path("id")
			.asString());
		String reply = path(body(post(owner, answered + "/comments", "{\"text\":\"Thanks, Rob\"}"), HttpStatus.CREATED)
			.path("id")
			.asString());

		assertThat(texts(body(mvc.get().uri(answered).exchange()))).containsExactly("Thanks, Rob");
		var listed = body(mvc.get().uri(product + "/reviews").exchange()).path("member");
		var byId = new HashMap<String, JsonNode>();
		listed.values().forEach(r -> byId.put(path(r.path("id").asString()), r));
		assertThat(texts(byId.get(answered))).containsExactly("Thanks, Rob");
		assertThat(byId.get(unanswered).has("comment")).isFalse();

		// Replies are not listed on their own: only embedded, and read or changed one by one.
		assertThat(mvc.get().uri(answered + "/comments")).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);

		// Changing the reply needs its version; deleting it lets the owner reply again.
		assertThat(mvc.put().uri(reply).with(as(owner)).contentType(LD_JSON).content("{\"text\":\"Thank you\"}"))
			.hasStatus(HttpStatus.PRECONDITION_REQUIRED);
		body(put(owner, reply, "{\"text\":\"Thank you, Rob\"}"), HttpStatus.OK);
		assertThat(texts(body(mvc.get().uri(answered).exchange()))).containsExactly("Thank you, Rob");
		assertThat(delete(owner, reply)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(body(mvc.get().uri(answered).exchange()).has("comment")).isFalse();
		body(post(owner, answered + "/comments", "{\"text\":\"Thanks again, Rob\"}"), HttpStatus.CREATED);
		assertThat(texts(body(mvc.get().uri(answered).exchange()))).containsExactly("Thanks again, Rob");
	}

	private static List<String> texts(JsonNode reviewOrComments) {
		JsonNode comments = reviewOrComments.isArray() ? reviewOrComments : reviewOrComments.path("comment");
		return comments.values().stream().map(c -> c.path("text").asString()).toList();
	}

	@Test
	void singleResourcesAreCacheableAndRevalidated() {
		String owner = email("owner");
		String rob = email("rob");
		register(owner, "Owner");
		String robProfile = path(register(rob, "Rob").path("id").asString());
		String product = path(body(post(owner, "/products", product("Cached")), HttpStatus.CREATED)
			.path("id")
			.asString());

		var first = mvc.get().uri(product).exchange();
		assertThat(first).hasStatusOk().hasHeader("Cache-Control", "no-cache");
		String etag = first.getResponse().getHeader("ETag");
		String lastModified = first.getResponse().getHeader("Last-Modified");
		assertThat(etag).matches("W/\"[0-9a-f]+\"");
		assertThat(lastModified).isNotBlank();

		var notModified = mvc.get().uri(product).header("If-None-Match", etag).exchange();
		assertThat(notModified).hasStatus(HttpStatus.NOT_MODIFIED).hasHeader("ETag", etag);
		assertThat(notModified.getResponse().getContentAsByteArray()).isEmpty();
		assertThat(mvc.get().uri(product).header("If-Modified-Since", lastModified))
			.hasStatus(HttpStatus.NOT_MODIFIED);
		assertThat(mvc.get().uri(product).header("If-None-Match", "W/\"0\"")).hasStatusOk();

		// A review changes the product's aggregate rating, and so its validators.
		String review = path(body(post(rob, product + "/reviews", review(4, "Good")), HttpStatus.CREATED)
			.path("id")
			.asString());
		String rated = etag(product);
		assertThat(rated).isNotEqualTo(etag);
		assertThat(mvc.get().uri(product).header("If-None-Match", etag)).hasStatusOk();

		// So does changing only the categories, which leaves the product row alone.
		var unchanged = body(mvc.get().uri(product).exchange());
		String sameJson = json.writeValueAsString(unchanged);
		body(put(owner, product, sameJson), HttpStatus.OK);
		assertThat(etag(product)).as("a PUT that changes nothing").isEqualTo(rated);
		body(put(owner, product, sameJson.replace("[\"Audio\"]", "[\"Home\"]")), HttpStatus.OK);
		assertThat(etag(product)).isNotEqualTo(rated);

		// A review's validators cover its comments, including a deleted one.
		String plain = etag(review);
		String comment = path(body(post(owner, review + "/comments", "{\"text\":\"Thanks\"}"), HttpStatus.CREATED)
			.path("id")
			.asString());
		String commented = etag(review);
		assertThat(commented).isNotEqualTo(plain);
		assertThat(etag(comment)).matches("W/\"[0-9a-f]+\"");
		body(put(owner, comment, "{\"text\":\"Thank you\"}"), HttpStatus.OK);
		String edited = etag(review);
		assertThat(edited).isNotEqualTo(commented);
		assertThat(delete(owner, comment)).hasStatus(HttpStatus.NO_CONTENT);
		String uncommented = etag(review);
		assertThat(uncommented).isNotEqualTo(edited);

		// And the name of its author, which renaming them changes.
		body(put(rob, robProfile, "{\"name\":\"Robert\"}"), HttpStatus.OK);
		assertThat(mvc.get().uri(review).header("If-None-Match", uncommented)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.author.name")
			.isEqualTo("Robert");
	}

	@Test
	void collectionsSayTheyAreShared() {
		String owner = email("owner");
		register(owner, "Owner");
		String product = path(body(post(owner, "/products", product("Listed")), HttpStatus.CREATED)
			.path("id")
			.asString());

		// Said explicitly (HttpCaching.shared): Spring Security's no-store would
		// tell the edge they were personal, and it would never keep them. They
		// have no validators: a cache asks again each time.
		for (String collection : List.of("/products", "/categories", "/people", product + "/reviews")) {
			var response = mvc.get().uri(collection).exchange();
			assertThat(response).hasStatusOk().doesNotContainHeader("ETag");
			assertThat(response.getResponse().getHeader("Cache-Control")).as(collection).isEqualTo("no-cache");
		}
		// What says nothing stays unstorable.
		assertThat(mvc.get().uri("/products/999999999").exchange().getResponse().getHeader("Cache-Control"))
			.contains("no-store");
	}

	@Test
	void peopleAreSharedButPeopleMeIsPrivate() {
		String owner = email("owner");
		var person = register(owner, "Owner");
		String profile = path(person.path("id").asString());

		var shared = mvc.get().uri(profile).with(as(owner)).exchange();
		assertThat(shared).hasStatusOk();
		assertThat(shared.getResponse().getHeaders("Vary")).doesNotContain("Authorization");
		assertThat(shared.getResponse().getHeader("Cache-Control")).isEqualTo("no-cache");
		String etag = shared.getResponse().getHeader("ETag");
		assertThat(mvc.get().uri(profile).header("If-None-Match", etag)).hasStatus(HttpStatus.NOT_MODIFIED);

		var mine = mvc.get().uri("/people/me").with(as(owner)).exchange();
		assertThat(mine).hasStatusOk();
		assertThat(mine.getResponse().getHeaders("Vary")).contains("Authorization");
		assertThat(mine.getResponse().getHeader("Cache-Control")).contains("private").contains("no-cache");
		assertThat(mvc.get().uri("/people/me").with(as(owner)).header("If-None-Match", etag))
			.hasStatus(HttpStatus.NOT_MODIFIED);

		// Owning a product changes the person's "owns", and so their validators.
		String product = path(body(post(owner, "/products", product("Owned")), HttpStatus.CREATED)
			.path("id")
			.asString());
		String owning = mvc.get().uri(profile).exchange().getResponse().getHeader("ETag");
		assertThat(owning).isNotEqualTo(etag);
		assertThat(delete(owner, product)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(mvc.get().uri(profile).exchange().getResponse().getHeader("ETag")).isNotEqualTo(owning);
	}

	@Test
	void writesNeedTheCurrentETag() {
		String owner = email("owner");
		register(owner, "Owner");
		var created = post(owner, "/products", product("Versioned"));
		assertThat(created).hasStatus(HttpStatus.CREATED);
		String product = path(body(created).path("id").asString());
		String location = created.getResponse().getHeader("Location");
		assertThat(created.getResponse().getHeader("Content-Location")).isEqualTo(location);
		String v1 = created.getResponse().getHeader("ETag");
		assertThat(v1).isEqualTo(etag(product)).matches("W/\"[0-9a-f]+\"");
		assertThat(mvc.head().uri(product)).hasStatusOk().hasHeader("ETag", v1);

		// Without a precondition, a write could overwrite a change it never saw.
		assertThat(mvc.put().uri(product).with(as(owner)).contentType(LD_JSON).content(product("Blind")))
			.hasStatus(HttpStatus.PRECONDITION_REQUIRED)
			.hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(mvc.delete().uri(product).with(as(owner))).hasStatus(HttpStatus.PRECONDITION_REQUIRED);

		// Two editors read v1. The first one's change wins...
		var first = putIfMatch(owner, product, v1, product("First"));
		assertThat(first).hasStatusOk().hasHeader("Content-Location", location);
		String v2 = first.getResponse().getHeader("ETag");
		assertThat(v2).isNotEqualTo(v1).isEqualTo(etag(product));
		assertThat(first.getResponse().getHeader("Last-Modified")).isNotBlank();

		// ...and the second, still holding v1, is refused rather than losing it.
		assertThat(putIfMatch(owner, product, v1, product("Second")))
			.hasStatus(HttpStatus.PRECONDITION_FAILED)
			.hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(mvc.delete().uri(product).with(as(owner)).header("If-Match", v1))
			.hasStatus(HttpStatus.PRECONDITION_FAILED);
		assertThat(body(mvc.get().uri(product).exchange()).path("name").asString()).isEqualTo("First");

		// The ETag from a PUT is good for the next one, without reading again.
		String thirdJson = product("Third");
		var third = putIfMatch(owner, product, v2, thirdJson);
		assertThat(third).hasStatusOk();
		assertThat(body(third).path("name").asString()).isEqualTo("Third");

		// An unchanged representation keeps its ETag, strong or weak; * matches any.
		String v3 = third.getResponse().getHeader("ETag");
		assertThat(putIfMatch(owner, product, v3.substring(2), thirdJson)).hasStatusOk()
			.hasHeader("ETag", v3);
		assertThat(putIfMatch(owner, product, "*", product("Fourth"))).hasStatusOk();

		// 404 and 403 come before any precondition.
		assertThat(putIfMatch(owner, "/products/999999", v1, product("Gone"))).hasStatus(HttpStatus.NOT_FOUND);
		String other = email("other");
		register(other, "Other");
		assertThat(mvc.put().uri(product).with(as(other)).contentType(LD_JSON).content(product("Mine")))
			.hasStatus(HttpStatus.FORBIDDEN);

		assertThat(mvc.delete().uri(product).with(as(owner)).header("If-Match", etag(product)))
			.hasStatus(HttpStatus.NO_CONTENT);
	}

	@Test
	void ifUnmodifiedSinceAlsoGuardsWrites() {
		String rob = email("rob");
		var person = register(rob, "Rob");
		String profile = path(person.path("id").asString());
		String lastModified = mvc.get().uri(profile).exchange().getResponse().getHeader("Last-Modified");

		assertThat(mvc.put()
			.uri(profile)
			.with(as(rob))
			.header("If-Unmodified-Since", "Thu, 01 Jan 2004 00:00:00 GMT")
			.contentType(LD_JSON)
			.content("{\"name\":\"Robert\"}")).hasStatus(HttpStatus.PRECONDITION_FAILED);
		assertThat(mvc.put()
			.uri(profile)
			.with(as(rob))
			.header("If-Unmodified-Since", lastModified)
			.contentType(LD_JSON)
			.content("{\"name\":\"Robert\"}")).hasStatusOk();
	}

	@Test
	void reviewAndCommentWritesNeedTheirETagsToo() {
		String owner = email("owner");
		String rob = email("rob");
		register(owner, "Owner");
		register(rob, "Rob");
		String product = path(body(post(owner, "/products", product("Guarded")), HttpStatus.CREATED)
			.path("id")
			.asString());
		var created = post(rob, product + "/reviews", review(4, "Good"));
		String review = path(body(created, HttpStatus.CREATED).path("id").asString());
		String read = created.getResponse().getHeader("ETag");

		// A comment changes the review's representation, so its old ETag is stale.
		var commented = post(owner, review + "/comments", "{\"text\":\"Thanks\"}");
		String comment = path(body(commented, HttpStatus.CREATED).path("id").asString());
		assertThat(mvc.put().uri(review).with(as(rob)).header("If-Match", read).contentType(LD_JSON)
			.content(review(5, "Better"))).hasStatus(HttpStatus.PRECONDITION_FAILED);
		assertThat(put(rob, review, review(5, "Better"))).hasStatusOk();

		String commentTag = commented.getResponse().getHeader("ETag");
		assertThat(mvc.delete().uri(comment).with(as(owner)).header("If-Match", "W/\"0\""))
			.hasStatus(HttpStatus.PRECONDITION_FAILED);
		assertThat(mvc.delete().uri(comment).with(as(owner)).header("If-Match", commentTag))
			.hasStatus(HttpStatus.NO_CONTENT);
	}

	private MvcTestResult putIfMatch(String email, String path, String ifMatch, String body) {
		return mvc.put()
			.uri(path)
			.with(as(email))
			.header("If-Match", ifMatch)
			.contentType(LD_JSON)
			.content(body)
			.exchange();
	}

	private String etag(String path) {
		var result = mvc.get().uri(path).exchange();
		assertThat(result).hasStatusOk();
		return result.getResponse().getHeader("ETag");
	}

	@Test
	void singleResourcesAreServedFromTheCache() throws Exception {
		String owner = email("owner");
		register(owner, "Owner");
		String product = path(body(post(owner, "/products", product("Cached Row")), HttpStatus.CREATED)
			.path("id")
			.asString());
		long id = Long.parseLong(product.replaceAll(".*/", ""));

		eventuallyCached(product, id);

		// The write reads the database, not the cache, so its response is current...
		assertThat(body(put(owner, product, product("Renamed Row")), HttpStatus.OK).path("name").asString())
			.isEqualTo("Renamed Row");
		// ...and this instance's own write is visible to the very next request.
		assertThat(body(mvc.get().uri(product).exchange()).path("name").asString()).isEqualTo("Renamed Row");
	}

	@Test
	void changesMadeElsewhereReachTheCacheByNotify() throws Exception {
		String owner = email("owner");
		register(owner, "Owner");
		String product = path(body(post(owner, "/products", product("Before")), HttpStatus.CREATED)
			.path("id")
			.asString());
		long id = Long.parseLong(product.replaceAll(".*/", ""));
		eventuallyCached(product, id);

		// As another instance (or a person with psql) would: straight to the database.
		jdbc.sql("UPDATE product SET name = 'After' WHERE product_id = :id").param("id", id).update();

		assertThat(eventually(() -> body(mvc.get().uri(product).exchange()).path("name").asString(), "After"))
			.isEqualTo("After");

		jdbc.sql("""
				INSERT INTO category (id, term, name)
				VALUES ('https://example.com/categories/testcategory', 'TestCategory', 'Test category')
				""").update();
		try {
			assertThat(eventually(() -> body(mvc.get().uri("/categories").exchange()).path("hasDefinedTerm").toString(),
					"testcategory"))
				.contains("https://example.com/categories/testcategory");
		}
		finally {
			jdbc.sql("DELETE FROM category WHERE term = 'TestCategory'").update();
		}
	}

	/** Polls until the value contains what is expected (NOTIFY arrives just after commit). */
	private static String eventually(Supplier<String> value, String expected) throws Exception {
		String last = value.get();
		for (int i = 0; i < 50 && !last.contains(expected); i++) {
			Thread.sleep(100);
			last = value.get();
		}
		return last;
	}

	/**
	 * The product left in the cache. The NOTIFYs its creation sent are applied
	 * by the journal listener a moment later, so the first GET's copy may be the
	 * one they drop; re-GET until one survives them (a beat with no evict left to
	 * arrive), which fails only if nothing ever caches the row.
	 */
	private void eventuallyCached(String product, long id) throws Exception {
		for (int i = 0; i < 50; i++) {
			Thread.sleep(100);
			body(mvc.get().uri(product).exchange());
			if (cacheManager.getCache(Caches.PRODUCTS).get(id) != null) {
				Thread.sleep(150);
				if (cacheManager.getCache(Caches.PRODUCTS).get(id) != null) {
					return;
				}
			}
		}
		assertThat(cacheManager.getCache(Caches.PRODUCTS).get(id)).as("product %s in the cache", id)
			.isNotNull();
	}

	@Test
	void journalIsNotExposed() {
		assertThat(mvc.get().uri("/journal").with(as(ADMIN))).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void deletingAProductKeepsItsReviewsReadable() {
		String owner = email("owner");
		String rob = email("rob");
		register(owner, "Owner");
		register(rob, "Rob");
		String product = path(body(post(owner, "/products", product("Discontinued")), HttpStatus.CREATED)
			.path("id")
			.asString());
		String review = path(
				body(post(rob, product + "/reviews", review(4, "Still here")), HttpStatus.CREATED)
					.path("id")
					.asString());

		assertThat(delete(owner, product)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(mvc.get().uri(product)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.get().uri(review)).hasStatusOk();
		assertThat(mvc.delete().uri(product).with(as(owner))).hasStatus(HttpStatus.NO_CONTENT);
	}

	@Test
	void onlyOwnersAndAuthorsChangeTheirData() {
		String owner = email("owner");
		String rob = email("rob");
		String eve = email("eve");
		var ownerPerson = register(owner, "Owner");
		var robPerson = register(rob, "Rob");
		register(eve, "Eve");
		String product = path(body(post(owner, "/products", product("Guarded")), HttpStatus.CREATED)
			.path("id")
			.asString());
		String review = path(body(post(rob, product + "/reviews", review(4, "Mine")), HttpStatus.CREATED)
			.path("id")
			.asString());
		String comment = path(body(post(owner, review + "/comments", "{\"text\":\"Thanks\"}"), HttpStatus.CREATED)
			.path("id")
			.asString());
		String ownerProfile = path(ownerPerson.path("id").asString());
		String robProfile = path(robPerson.path("id").asString());

		// Nobody else, administrators included, may change or delete these.
		for (String other : List.of(eve, ADMIN)) {
			assertThat(put(other, product, product("Taken"))).hasStatus(HttpStatus.FORBIDDEN);
			assertThat(delete(other, product)).hasStatus(HttpStatus.NOT_FOUND);
			assertThat(put(other, review, review(1, "Changed"))).hasStatus(HttpStatus.FORBIDDEN);
			assertThat(delete(other, review)).hasStatus(HttpStatus.FORBIDDEN);
			assertThat(put(other, comment, "{\"text\":\"Changed\"}")).hasStatus(HttpStatus.FORBIDDEN);
			assertThat(delete(other, comment)).hasStatus(HttpStatus.FORBIDDEN);
			assertThat(put(other, robProfile, "{\"name\":\"Changed\"}")).hasStatus(HttpStatus.FORBIDDEN);
			assertThat(delete(other, robProfile)).hasStatus(HttpStatus.FORBIDDEN);
		}
		// The product's owner may not change the review on it, nor the author the owner's reply.
		assertThat(put(owner, review, review(1, "Changed"))).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(delete(owner, review)).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(put(rob, comment, "{\"text\":\"Changed\"}")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(put(rob, ownerProfile, "{\"name\":\"Changed\"}")).hasStatus(HttpStatus.FORBIDDEN);

		// Without a token nothing may be written.
		assertThat(mvc.put().uri(product).contentType(LD_JSON).content(product("Anon")))
			.hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(mvc.delete().uri(review)).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(mvc.post().uri(review + "/comments").contentType(LD_JSON).content("{\"text\":\"Anon\"}"))
			.hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(mvc.put().uri(robProfile).contentType(LD_JSON).content("{\"name\":\"Anon\"}"))
			.hasStatus(HttpStatus.UNAUTHORIZED);

		// Each may change their own.
		assertThat(put(owner, product, product("Guarded Still"))).hasStatus(HttpStatus.OK);
		assertThat(put(rob, review, review(5, "Better"))).hasStatus(HttpStatus.OK);
		assertThat(put(owner, comment, "{\"text\":\"Thank you\"}")).hasStatus(HttpStatus.OK);
		assertThat(put(rob, robProfile, "{\"name\":\"Robert\"}")).hasStatus(HttpStatus.OK);
		assertThat(delete(owner, comment)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(delete(rob, review)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(delete(rob, robProfile)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(delete(owner, product)).hasStatus(HttpStatus.NO_CONTENT);
	}

	// --- helpers ---

	private JsonNode register(String email, String name) {
		return body(post(email, "/people", "{\"type\":\"Person\",\"name\":\"" + name + "\"}"), HttpStatus.CREATED);
	}

	private MvcTestResult post(String email, String path, String body) {
		return mvc.post().uri(path).with(as(email)).contentType(LD_JSON).content(body).exchange();
	}

	/** A PUT with If-Match, as a client that has just read the resource sends it. */
	private MvcTestResult put(String email, String path, String body) {
		return mvc.put()
			.uri(path)
			.with(as(email))
			.with(ifMatchCurrent(path))
			.contentType(LD_JSON)
			.content(body)
			.exchange();
	}

	private MvcTestResult delete(String email, String path) {
		return mvc.delete().uri(path).with(as(email)).with(ifMatchCurrent(path)).exchange();
	}

	private RequestPostProcessor ifMatchCurrent(String path) {
		String etag = mvc.get().uri(path).exchange().getResponse().getHeader("ETag");
		return request -> {
			if (etag != null) {
				request.addHeader("If-Match", etag);
			}
			return request;
		};
	}

	private JsonNode body(MvcTestResult result, HttpStatus expected) {
		assertThat(result).hasStatus(expected);
		return body(result);
	}

	private JsonNode body(MvcTestResult result) {
		assertThat(result).hasStatus2xxSuccessful();
		return json.readTree(result.getResponse().getContentAsByteArray());
	}

	private List<String> listIds(String uri, Object... variables) {
		return body(mvc.get().uri(uri, variables).exchange()).path("member")
			.values()
			.stream()
			.map(r -> r.path("id").asString())
			.toList();
	}

	private void assertAggregate(String product, String ratingValue, int ratingCount, int reviewCount) {
		var aggregate = body(mvc.get().uri(product).exchange()).path("aggregateRating");
		assertThat(aggregate.path("type").asString()).isEqualTo("AggregateRating");
		assertThat(aggregate.path("ratingValue").decimalValue()).isEqualByComparingTo(new BigDecimal(ratingValue));
		assertThat(aggregate.path("ratingCount").asInt()).isEqualTo(ratingCount);
		assertThat(aggregate.path("reviewCount").asInt()).isEqualTo(reviewCount);
	}

	private JsonObject expand(JsonNode document) throws Exception {
		byte[] context = mvc.get().uri("/context.jsonld").exchange().getResponse().getContentAsByteArray();
		DocumentLoader loader = (url, options) -> {
			assertThat(url.toString()).isEqualTo(document.path("@context").asString());
			Document loaded = JsonDocument.of(new ByteArrayInputStream(context));
			loaded.setDocumentUrl(url);
			return loaded;
		};
		JsonArray expanded = JsonLd.expand(JsonDocument.of(new StringReader(json.writeValueAsString(document))))
			.loader(loader)
			.get();
		assertThat(expanded).hasSize(1);
		JsonObject node = expanded.getJsonObject(0);
		assertThat(node.getString("@id")).isEqualTo(document.path("id").asString());
		return node;
	}

	private static String id(JsonObject node, String property) {
		return node.getJsonArray(property).getJsonObject(0).getString("@id");
	}

	private static String value(JsonObject node, String property) {
		return node.getJsonArray(property).getJsonObject(0).getString("@value");
	}

	private static String product(String name) {
		return product(name, "https://example.com/categories/audio");
	}

	private static String product(String name, String category) {
		return """
				{
				  "@context": "http://localhost/context.jsonld",
				  "type": "Product",
				  "name": "%s",
				  "description": "Noise-cancelling headphones.",
				  "url": "https://products.example/%s",
				  "category": ["%s"],
				  "brand": "Aria",
				  "gtin": "00012345600012",
				  "sku": "ARIA-100",
				  "mpn": "ARIA-100-BLK"
				}
				""".formatted(name, UUID.randomUUID(), category);
	}

	private String newCategory() {
		String term = "Test" + UUID.randomUUID().toString().replace("-", "");
		String id = "https://example.com/categories/" + term.toLowerCase(java.util.Locale.ROOT);
		jdbc.sql("INSERT INTO category (id, term, name) VALUES (:id, :term, :name)")
			.param("id", id)
			.param("term", term)
			.param("name", term)
			.update();
		return id;
	}

	private static String review(int rating, String body) {
		return """
				{"type": "Review", "reviewBody": "%s", "reviewRating": {"type": "Rating", "ratingValue": %d}}
				""".formatted(body, rating);
	}

	private static String path(String iri) {
		return iri.replaceFirst("^https?://[^/]+", "");
	}

	private static String email(String name) {
		return name + "-" + UUID.randomUUID() + "@example.com";
	}

	/**
	 * A token for the email, with the role its name suggests: "owner-..." and
	 * "other-..." registered as owners, the administrator as a reviewer with the
	 * admin realm role, everyone else as a reviewer.
	 */
	private static RequestPostProcessor as(String email) {
		return as(email, (email.startsWith("owner") || email.startsWith("other")) ? "owner" : "reviewer");
	}

	/** A token with the given role claim, or none. */
	private static RequestPostProcessor as(String email, String role) {
		return jwt().jwt(token -> {
			token.subject(email).claim("email", email);
			if (role != null) {
				token.claim("role", role);
			}
			if (email.equals(ADMIN)) {
				token.claim("roles", List.of("default-roles-reviews", "admin"));
			}
		});
	}

}
