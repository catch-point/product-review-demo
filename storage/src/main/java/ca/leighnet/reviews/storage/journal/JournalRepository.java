package ca.leighnet.reviews.storage.journal;

import java.util.List;

import ca.leighnet.reviews.storage.cache.CacheInvalidator;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Attributes changes to the people who make them. journal_write() in
 * schema.sql records every change; the table is not exposed by the API.
 */
@Repository
public class JournalRepository {

	private final JdbcClient jdbc;

	private final CacheInvalidator invalidator;

	private final JsonMapper mapper;

	JournalRepository(JdbcClient jdbc, CacheInvalidator invalidator, JsonMapper mapper) {
		this.jdbc = jdbc;
		this.invalidator = invalidator;
		this.mapper = mapper;
	}

	/**
	 * Names {@code person:<personId>} as the actor of every change in the
	 * current transaction (journal_write() reads {@code app.actor}), and drops
	 * the cached rows those changes affect once it commits.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void actAs(long personId) {
		jdbc.sql("SELECT set_config('app.actor', :actor, true)")
			.param("actor", actor(personId))
			.query(String.class)
			.single();
		invalidateOnCommit();
	}

	public static String actor(long personId) {
		return "person:" + personId;
	}

	/**
	 * Evicts this transaction's changes as soon as it commits, so the next
	 * request sees them. Other instances learn of them by NOTIFY, a moment later.
	 */
	private void invalidateOnCommit() {
		if (TransactionSynchronizationManager.hasResource(this)) {
			return;
		}
		TransactionSynchronizationManager.bindResource(this, Boolean.TRUE);
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

			private List<String> changes = List.of();

			@Override
			public void beforeCommit(boolean readOnly) {
				changes = jdbc.sql("SELECT journal_refs(j)::text FROM journal j WHERE j.txid = txid_current()")
					.query(String.class)
					.list();
			}

			@Override
			public void afterCommit() {
				changes.forEach(change -> invalidator.apply(mapper.readTree(change)));
			}

			@Override
			public void afterCompletion(int status) {
				TransactionSynchronizationManager.unbindResource(JournalRepository.this);
			}

		});
	}

}
