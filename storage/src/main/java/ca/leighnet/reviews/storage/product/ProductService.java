package ca.leighnet.reviews.storage.product;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import ca.leighnet.reviews.storage.category.CategoryService;
import ca.leighnet.reviews.storage.jsonld.Iris;
import ca.leighnet.reviews.storage.jsonld.Page;
import ca.leighnet.reviews.storage.journal.JournalRepository;
import ca.leighnet.reviews.storage.person.CurrentUser;
import ca.leighnet.reviews.storage.person.Role;
import ca.leighnet.reviews.storage.web.ApiException;
import ca.leighnet.reviews.storage.web.Preconditions;
import ca.leighnet.reviews.storage.web.Versioned;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

	private final ProductRepository products;

	private final CategoryService categories;

	private final JournalRepository journal;

	private final CurrentUser currentUser;

	private final Iris iris;

	ProductService(ProductRepository products, CategoryService categories, JournalRepository journal,
			CurrentUser currentUser, Iris iris) {
		this.products = products;
		this.categories = categories;
		this.journal = journal;
		this.currentUser = currentUser;
		this.iris = iris;
	}

	/** Active products, optionally filtered by category and aggregate rating. */
	@Transactional(readOnly = true)
	public Page<ProductDoc> list(String category, ProductRepository.Filter filter) {
		var resolved = new ProductRepository.Filter(Optional.ofNullable(category).map(categories::resolve).orElse(null),
				filter.minRating(), filter.maxRating(), filter.limit(), filter.offset());
		var items = products.list(resolved).stream().map(row -> toDoc(row, null)).toList();
		return new Page<>(items, products.count(resolved), filter.limit(), filter.offset());
	}

	/**
	 * The product with validators covering it and its aggregate rating.
	 * Single-resource reads run outside a transaction: their rows usually come
	 * from the cache, and a cache hit then needs no database connection.
	 */
	public Versioned<ProductDoc> read(long id) {
		return version(products.find(id).orElseThrow(() -> notFound(id)));
	}

	private Versioned<ProductDoc> version(ProductRepository.Row row) {
		var rating = row.aggregateRating();
		return Versioned.of(toDoc(row, iris.context()),
				Versioned.latest(row.dateCreated(), row.dateModified(), (rating != null) ? rating.changed() : null));
	}

	/**
	 * Locks the product for this transaction, checks that the caller owns it,
	 * then checks the request's preconditions against it as it is now (so a
	 * stranger learns nothing from them).
	 */
	private ProductRepository.Row lock(long id, long me, Preconditions preconditions) {
		if (!products.lock(id)) {
			throw notFound(id);
		}
		var row = products.find(id).orElseThrow(() -> notFound(id));
		if (row.owner() == null || row.owner() != me) {
			throw ApiException.forbidden("Only the owner of product " + id + " may change it");
		}
		preconditions.check(version(row));
		return row;
	}

	/** Owners (by role) may add products, which they then own. */
	@Transactional
	public Versioned<ProductDoc> create(ProductDoc input) {
		iris.checkContext(input.context());
		long me = currentUser.requirePersonId();
		currentUser.requireRole(Role.OWNER);
		var fields = fields(input);
		var categories = categories(input);
		journal.actAs(me);
		long id = products.insert(fields, me);
		products.replaceCategories(id, categories);
		return read(id);
	}

	@Transactional
	public Versioned<ProductDoc> update(long id, ProductDoc input, Preconditions preconditions) {
		iris.checkContext(input.context());
		requireExists(id);
		long me = currentUser.requirePersonId();
		currentUser.requireRole(Role.OWNER);
		lock(id, me, preconditions);
		var fields = fields(input);
		if (Long.valueOf(id).equals(fields.sameAs())) {
			throw ApiException.badRequest("A product cannot be sameAs itself");
		}
		var categories = categories(input);
		journal.actAs(me);
		products.update(id, fields);
		if (products.replaceCategories(id, categories)) {
			products.touch(id);
		}
		return read(id);
	}

	/**
	 * Soft-deletes a product: its owner's first DELETE hides it from listings,
	 * direct reads, review collections and ownership, while its reviews and
	 * replies stay readable through their own IRIs. A repeated DELETE by that
	 * owner succeeds without checking preconditions or changing anything.
	 * Anyone else, and an unknown product, sees 404.
	 */
	@Transactional
	public void delete(long id, Preconditions preconditions) {
		long me = currentUser.requirePersonId();
		var row = products.findAny(id).orElseThrow(() -> notFound(id));
		if (row.owner() == null || row.owner() != me) {
			throw notFound(id);
		}
		if (products.isDiscontinued(id)) {
			return;
		}
		currentUser.requireRole(Role.OWNER);
		if (!products.lockAny(id)) {
			throw notFound(id);
		}
		row = products.findAny(id).orElseThrow(() -> notFound(id));
		if (products.isDiscontinued(id)) {
			return;
		}
		preconditions.check(version(row));
		journal.actAs(me);
		products.discontinue(id);
	}

	private ProductRepository.Fields fields(ProductDoc input) {
		Iris.checkType(input.type(), ProductDoc.TYPE);
		Long sameAs = null;
		if (input.sameAs() != null && !input.sameAs().isEmpty()) {
			sameAs = iris.id(Iris.PRODUCTS, input.sameAs().getFirst(), "sameAs");
			requireExists(sameAs);
		}
		return new ProductRepository.Fields(input.name().strip(), input.description(),
				iris.imagePath(input.image(), "image"), Iris.absoluteUri(input.url(), "url"), sameAs, input.brand(),
				input.gtin(), input.sku(), input.mpn());
	}

	private List<String> categories(ProductDoc input) {
		if (input.category() == null) {
			return List.of();
		}
		return List.copyOf(new LinkedHashSet<>(input.category().stream().map(categories::resolve).toList()));
	}

	private ProductDoc toDoc(ProductRepository.Row row, String context) {
		var f = row.fields();
		var a = row.aggregateRating();
		var aggregate = (a != null) ? new ProductDoc.AggregateRating(ProductDoc.AggregateRating.TYPE,
				a.ratingValue(), a.bestRating(), a.worstRating(), a.ratingCount(), a.reviewCount()) : null;
		return new ProductDoc(context, iris.of(Iris.PRODUCTS, row.id()), ProductDoc.TYPE, f.name(),
				f.description(), iris.image(f.image()), f.url(),
				(f.sameAs() != null) ? List.of(iris.of(Iris.PRODUCTS, f.sameAs())) : null,
				categories.compact(row.categories()), f.brand(), f.gtin(), f.sku(), f.mpn(),
				(row.owner() != null) ? iris.of(Iris.PEOPLE, row.owner()) : null, aggregate, row.dateCreated(),
				row.dateModified(), iris.of(Iris.PRODUCTS, row.id()) + "/reviews");
	}

	private void requireExists(long id) {
		if (!products.exists(id)) {
			throw notFound(id);
		}
	}

	private static ApiException notFound(long id) {
		return ApiException.notFound("No product " + id);
	}

}
