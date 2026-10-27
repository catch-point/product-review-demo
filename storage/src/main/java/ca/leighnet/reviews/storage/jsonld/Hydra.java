package ca.leighnet.reviews.storage.jsonld;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Builds hydra:Collection responses for the current request. The collection
 * is named by the request URL without paging parameters (so filters are part
 * of it); its view and page links add {@code limit} and {@code offset}.
 */
@Component
public class Hydra {

	public static final String LIMIT = "limit";

	public static final String OFFSET = "offset";

	/**
	 * The deepest page a client may ask for: the database skips every row
	 * before it, so deeper offsets cost more. Filters narrow a collection
	 * instead. The page links never go past it.
	 */
	public static final int MAX_OFFSET = 10_000;

	private final Iris iris;

	Hydra(Iris iris) {
		this.iris = iris;
	}

	public <T> HydraCollection<T> collection(Page<T> page) {
		return collection(page, null);
	}

	public <T> HydraCollection<T> collection(Page<T> page, HydraCollection.IriTemplate search) {
		return collection(page, search, null);
	}

	public <T> HydraCollection<T> collection(Page<T> page, HydraCollection.IriTemplate search, String seeAlso) {
		String id = request().replaceQueryParam(LIMIT).replaceQueryParam(OFFSET).build().toUriString();
		int limit = page.limit();
		int offset = page.offset();
		int lastOffset = (int) Math.min((page.total() == 0) ? 0 : (page.total() - 1) / limit * limit,
				MAX_OFFSET / limit * limit);
		// Past the end, "previous" is the last page.
		int previousOffset = Math.max(0, Math.min(offset - limit, lastOffset));
		var view = new HydraCollection.View(page(limit, offset), HydraCollection.View.TYPE, page(limit, 0),
				(offset > 0) ? page(limit, previousOffset) : null,
				(offset + limit < page.total() && offset + limit <= MAX_OFFSET) ? page(limit, offset + limit) : null,
				page(limit, lastOffset));
		return new HydraCollection<>(iris.context(), id, HydraCollection.TYPE, page.total(), page.items(), view,
				search, seeAlso);
	}

	/** A search template for this collection's path with the given query variables. */
	public HydraCollection.IriTemplate search(String path, HydraCollection.Mapping... mappings) {
		var variables = new StringBuilder();
		for (var mapping : mappings) {
			variables.append(variables.isEmpty() ? "" : ",").append(mapping.variable());
		}
		return new HydraCollection.IriTemplate(iris.collection(path) + "{?" + variables + "}",
				List.of(mappings));
	}

	private String page(int limit, int offset) {
		return request().replaceQueryParam(LIMIT, limit).replaceQueryParam(OFFSET, offset).build().toUriString();
	}

	private static UriComponentsBuilder request() {
		return ServletUriComponentsBuilder.fromCurrentRequest();
	}

}
