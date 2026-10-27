package ca.leighnet.reviews.storage.person;

import java.util.List;

import ca.leighnet.reviews.storage.jsonld.Iris;
import ca.leighnet.reviews.storage.jsonld.Page;
import ca.leighnet.reviews.storage.journal.JournalRepository;
import ca.leighnet.reviews.storage.web.ApiException;
import ca.leighnet.reviews.storage.web.Preconditions;
import ca.leighnet.reviews.storage.web.Versioned;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonService {

	private final PersonRepository people;

	private final JournalRepository journal;

	private final CurrentUser currentUser;

	private final Iris iris;

	PersonService(PersonRepository people, JournalRepository journal, CurrentUser currentUser, Iris iris) {
		this.people = people;
		this.journal = journal;
		this.currentUser = currentUser;
		this.iris = iris;
	}

	/** Registers the caller under the email in their token. */
	@Transactional
	public Versioned<PersonDoc> register(PersonDoc input) {
		iris.checkContext(input.context());
		String email = currentUser.requireEmail();
		if (people.findIdByEmail(email).isPresent()) {
			throw ApiException.conflict(email + " is already registered");
		}
		long id = people.nextId();
		journal.actAs(id);
		people.insert(id, input.name(), email);
		return read(id);
	}

	private PersonDoc get(long id, boolean withEmail) {
		var row = people.find(id).orElseThrow(() -> notFound(id));
		var owns = people.ownership(id).productIds().stream().map(r -> iris.of(Iris.PRODUCTS, r)).toList();
		return new PersonDoc(iris.context(), iris.of(Iris.PEOPLE, id), PersonDoc.TYPE, row.name(),
				withEmail ? row.email() : null, owns, row.dateCreated(), row.dateModified());
	}

	/** Registered people, oldest first, without their emails. */
	@Transactional(readOnly = true)
	public Page<PersonDoc> list(int limit, int offset) {
		var rows = people.list(limit, offset);
		var owned = people.ownedProductIds(rows.stream().map(PersonRepository.Row::id).toList());
		var items = rows.stream()
			.map(row -> new PersonDoc(null, iris.of(Iris.PEOPLE, row.id()), PersonDoc.TYPE, row.name(), null,
					owned.getOrDefault(row.id(), List.of()).stream().map(r -> iris.of(Iris.PRODUCTS, r)).toList(),
					row.dateCreated(), row.dateModified()))
			.toList();
		return new Page<>(items, people.count(), limit, offset);
	}

	/**
	 * The person as everyone sees them, without their email, so shared caches
	 * may keep it: with validators covering them and the products they own
	 * (the count catches a deleted product).
	 */
	public Versioned<PersonDoc> read(long id) {
		return read(id, false);
	}

	/** The caller, with their email: the only response that shows it. */
	public Versioned<PersonDoc> readMe() {
		return read(currentUser.requirePersonId(), true);
	}

	private Versioned<PersonDoc> read(long id, boolean withEmail) {
		var doc = get(id, withEmail);
		var owned = people.ownership(id);
		return Versioned.of(doc, Versioned.latest(doc.dateCreated(), doc.dateModified(), owned.latest()),
				owned.productIds().size());
	}

	@Transactional
	public Versioned<PersonDoc> update(long id, PersonDoc input, Preconditions preconditions) {
		iris.checkContext(input.context());
		people.find(id).orElseThrow(() -> notFound(id));
		long me = currentUser.requirePersonId();
		requireSelf(id, me);
		lock(id, preconditions);
		journal.actAs(me);
		people.updateName(id, input.name());
		return read(id);
	}

	/**
	 * Deletes a person. Their products lose their owner; a person with
	 * reviews or comments cannot be deleted (409).
	 */
	@Transactional
	public void delete(long id, Preconditions preconditions) {
		people.find(id).orElseThrow(() -> notFound(id));
		long me = currentUser.requirePersonId();
		requireSelf(id, me);
		lock(id, preconditions);
		journal.actAs(me);
		people.delete(id);
	}

	/** Only the person themselves may change or delete their record. */
	private static void requireSelf(long id, long me) {
		if (id != me) {
			throw ApiException.forbidden("Only person " + id + " may change their record");
		}
	}

	/**
	 * Locks the person for this transaction and checks the request's
	 * preconditions against them as they are now.
	 */
	private void lock(long id, Preconditions preconditions) {
		if (!people.lock(id)) {
			throw notFound(id);
		}
		preconditions.check(read(id));
	}

	private static ApiException notFound(long id) {
		return ApiException.notFound("No person " + id);
	}

}
