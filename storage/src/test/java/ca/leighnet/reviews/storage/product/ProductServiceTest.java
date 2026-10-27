package ca.leighnet.reviews.storage.product;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import ca.leighnet.reviews.storage.category.CategoryService;
import ca.leighnet.reviews.storage.jsonld.Iris;
import ca.leighnet.reviews.storage.journal.JournalRepository;
import ca.leighnet.reviews.storage.person.CurrentUser;
import ca.leighnet.reviews.storage.person.Role;
import ca.leighnet.reviews.storage.web.ApiException;
import ca.leighnet.reviews.storage.web.Preconditions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.ETag;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

	private static final String AUDIO = "https://example.com/categories/audio";

	@Mock
	private ProductRepository products;

	@Mock
	private CategoryService categories;

	@Mock
	private JournalRepository journal;

	@Mock
	private CurrentUser currentUser;

	@Mock
	private Iris iris;

	@InjectMocks
	private ProductService service;

	@Captor
	private ArgumentCaptor<ProductRepository.Fields> fields;

	@BeforeEach
	void setUp() {
		org.mockito.Mockito.lenient().when(currentUser.requirePersonId()).thenReturn(42L);
	}

	@Test
	void creatorsOwnTheirProduct() {
		given(categories.resolve("Audio")).willReturn(AUDIO);
		given(products.insert(any(), anyLong())).willReturn(3L);
		given(products.find(3)).willReturn(Optional.of(row(3, 42L)));

		service.create(doc("  Aria Headphones ", List.of("Audio", "Audio")));

		verify(journal).actAs(42);
		verify(currentUser).requireRole(Role.OWNER);
		verify(products).insert(fields.capture(), anyLong());
		assertThat(fields.getValue().name()).isEqualTo("Aria Headphones");
		assertThat(fields.getValue().brand()).isEqualTo("Aria");
		assertThat(fields.getValue().gtin()).isEqualTo("00012345600012");
		assertThat(fields.getValue().sku()).isEqualTo("ARIA-WH-01");
		assertThat(fields.getValue().mpn()).isEqualTo("WH-1000");
		verify(products).replaceCategories(3, List.of(AUDIO));
	}

	@Test
	void onlyOwnersByRoleCreate() {
		willThrow(ApiException.forbidden("no")).given(currentUser).requireRole(Role.OWNER);

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.create(doc("Mine", null)))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		verify(products, never()).insert(any(), anyLong());
	}

	@Test
	void onlyTheOwnerUpdatesEvenWithTheCurrentETag() {
		given(products.exists(3)).willReturn(true);
		given(products.lock(3)).willReturn(true);
		given(products.find(3)).willReturn(Optional.of(row(3, 7L)));
		var stale = new Preconditions(List.of(ETag.create("W/\"1\"")), null, true);

		// 403, not 412: a stranger learns nothing from the preconditions.
		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.update(3, doc("Mine now", null), stale))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		verify(products, never()).update(anyLong(), any());
	}

	@Test
	void unknownCategoriesAreRejectedBeforeWriting() {
		given(categories.resolve("Klingon")).willThrow(ApiException.badRequest("Unknown category Klingon"));

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.create(doc("Qapla", List.of("Klingon"))))
			.withMessageContaining("Klingon");
		verify(products, never()).insert(any(), anyLong());
	}

	@Test
	void staleWritesAreRefusedAfterLockingTheRow() {
		given(products.exists(3)).willReturn(true);
		given(products.lock(3)).willReturn(true);
		given(products.find(3)).willReturn(Optional.of(row(3, 42L)));
		var stale = new Preconditions(List.of(ETag.create("W/\"1\"")), null, true);

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.update(3, doc("Mine", null), stale))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED));
		verify(journal, never()).actAs(anyLong());
		verify(products, never()).update(anyLong(), any());
	}

	@Test
	void ownersDiscontinueTheirProducts() {
		given(products.findAny(3)).willReturn(Optional.of(row(3, 42L)));
		given(products.isDiscontinued(3)).willReturn(false);
		given(products.lockAny(3)).willReturn(true);

		service.delete(3, Preconditions.NONE);

		verify(currentUser).requireRole(Role.OWNER);
		verify(journal).actAs(42);
		verify(products).discontinue(3);
		verify(products, never()).find(3);
	}

	@Test
	void ownersRepeatADeleteWithoutPreconditionsOrChanges() {
		given(products.findAny(3)).willReturn(Optional.of(row(3, 42L)));
		given(products.isDiscontinued(3)).willReturn(true);

		service.delete(3, Preconditions.NONE);
		service.delete(3, new Preconditions(List.of(ETag.create("W/\"stale\"")), null, true));

		verify(products, never()).lockAny(3);
		verify(products, never()).discontinue(3);
		verifyNoInteractions(journal);
	}

	@Test
	void strangersAndOwnerlessProductsAreNotFound() {
		given(products.findAny(3)).willReturn(Optional.of(row(3, 7L)));
		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.delete(3, Preconditions.NONE))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

		given(products.findAny(4)).willReturn(Optional.of(row(4, null)));
		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.delete(4, Preconditions.NONE))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
		verify(products, never()).lockAny(anyLong());
		verifyNoInteractions(journal);
	}

	@Test
	void hiddenProductsCannotBeUpdated() {
		given(products.exists(3)).willReturn(false);

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> service.update(3, doc("Mine", null), Preconditions.NONE))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
		verify(products, never()).lock(3);
	}

	private static ProductDoc doc(String name, List<String> category) {
		return new ProductDoc(null, null, "Product", name, null, null, null, null, category, "Aria",
				"00012345600012", "ARIA-WH-01", "WH-1000", null, null, null, null, null);
	}

	private static ProductRepository.Row row(long id, Long owner) {
		return new ProductRepository.Row(id,
				new ProductRepository.Fields("Aria Headphones", null, null, null, null, "Aria", "00012345600012",
						"ARIA-WH-01", "WH-1000"),
				owner, List.of(AUDIO), null, OffsetDateTime.now(), null);
	}

}
