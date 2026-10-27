package ca.leighnet.reviews.storage.cache;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * LISTENs for journal NOTIFY messages, which PostgreSQL sends for every change
 * committed by any instance of this service or by anything else, and drops
 * the cached rows they affect. It holds one connection from the pool.
 */
@Component
class JournalListener implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(JournalListener.class);

	private static final int POLL_MILLIS = 1000;

	private static final long RECONNECT_MILLIS = 2000;

	private final DataSource dataSource;

	private final CacheInvalidator invalidator;

	private final JsonMapper mapper;

	private volatile boolean running;

	private Thread thread;

	JournalListener(DataSource dataSource, CacheInvalidator invalidator, JsonMapper mapper) {
		this.dataSource = dataSource;
		this.invalidator = invalidator;
		this.mapper = mapper;
	}

	@Override
	public void start() {
		running = true;
		thread = Thread.ofVirtual().name("journal-listener").start(this::listen);
	}

	@Override
	public void stop() {
		running = false;
		if (thread != null) {
			thread.interrupt();
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private void listen() {
		while (running) {
			try (Connection connection = dataSource.getConnection()) {
				PGConnection postgres = connection.unwrap(PGConnection.class);
				try (Statement statement = connection.createStatement()) {
					statement.execute("LISTEN journal");
				}
				// Changes made while no one was listening were not announced.
				invalidator.clearAll();
				log.info("Listening for journal changes");
				while (running) {
					PGNotification[] notifications = postgres.getNotifications(POLL_MILLIS);
					if (notifications != null) {
						for (PGNotification notification : notifications) {
							invalidator.apply(mapper.readTree(notification.getParameter()));
						}
					}
				}
			}
			catch (SQLException | RuntimeException ex) {
				if (running) {
					log.warn("Journal listener failed; reconnecting: {}", ex.getMessage());
					invalidator.clearAll();
					pause();
				}
			}
		}
	}

	private void pause() {
		try {
			Thread.sleep(RECONNECT_MILLIS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			running = false;
		}
	}

}
