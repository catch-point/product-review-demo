package ca.leighnet.reviews.storage.jsonld;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

class HydraTest {

	private static final String BASE = "http://localhost/products?category=Audio";

	private final Hydra hydra = new Hydra(new Iris());

	@AfterEach
	void unbindRequest() {
		RequestContextHolder.resetRequestAttributes();
	}

	@Test
	void firstPageLinksForwardOnly() {
		var view = view("limit=2&offset=0", 5);

		assertThat(view.id()).isEqualTo(BASE + "&limit=2&offset=0");
		assertThat(view.first()).isEqualTo(BASE + "&limit=2&offset=0");
		assertThat(view.previous()).isNull();
		assertThat(view.next()).isEqualTo(BASE + "&limit=2&offset=2");
		assertThat(view.last()).isEqualTo(BASE + "&limit=2&offset=4");
	}

	@Test
	void lastPageLinksBackwardOnly() {
		var view = view("limit=2&offset=4", 5);

		assertThat(view.previous()).isEqualTo(BASE + "&limit=2&offset=2");
		assertThat(view.next()).isNull();
	}

	@Test
	void pastTheEndLinksBackToTheLastPage() {
		var view = view("limit=2&offset=10", 5);

		assertThat(view.previous()).isEqualTo(BASE + "&limit=2&offset=4");
		assertThat(view.next()).isNull();
	}

	@Test
	void unalignedOffsetsPageByLimit() {
		var view = view("limit=2&offset=1", 5);

		assertThat(view.previous()).isEqualTo(BASE + "&limit=2&offset=0");
		assertThat(view.next()).isEqualTo(BASE + "&limit=2&offset=3");
	}

	@Test
	void emptyCollectionsHaveOnePage() {
		var view = view("", 0);

		assertThat(view.first()).isEqualTo(BASE + "&limit=2&offset=0");
		assertThat(view.last()).isEqualTo(view.first());
		assertThat(view.previous()).isNull();
		assertThat(view.next()).isNull();
	}

	@Test
	void namesTheCollectionWithoutPagingParameters() {
		bind("limit=2&offset=2");
		var collection = hydra.collection(new Page<>(List.of("a"), 3, 2, 2));

		assertThat(collection.id()).isEqualTo(BASE);
		assertThat(collection.type()).isEqualTo("Collection");
		assertThat(collection.totalItems()).isEqualTo(3);
		assertThat(collection.context()).isEqualTo("http://localhost/context.jsonld");
	}

	@Test
	void describesSearchVariables() {
		bind("");
		var search = hydra.search("/products", new HydraCollection.Mapping("category", "schema:category"),
				new HydraCollection.Mapping("limit", "hydra:limit"));

		assertThat(search.template()).isEqualTo("http://localhost/products{?category,limit}");
		assertThat(search.variableRepresentation()).isEqualTo("BasicRepresentation");
		assertThat(search.mapping()).extracting(HydraCollection.Mapping::property)
			.containsExactly("schema:category", "hydra:limit");
	}

	private HydraCollection.View view(String paging, long total) {
		bind(paging);
		var limitOffset = paging.isEmpty() ? new int[] { 2, 0 } : parse(paging);
		return hydra.collection(new Page<>(List.of(), total, limitOffset[0], limitOffset[1])).view();
	}

	private static int[] parse(String paging) {
		var parts = paging.split("&");
		return new int[] { Integer.parseInt(parts[0].substring(6)), Integer.parseInt(parts[1].substring(7)) };
	}

	private static void bind(String paging) {
		var request = new MockHttpServletRequest("GET", "/products");
		request.setQueryString("category=Audio" + (paging.isEmpty() ? "" : "&" + paging));
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
	}

}
