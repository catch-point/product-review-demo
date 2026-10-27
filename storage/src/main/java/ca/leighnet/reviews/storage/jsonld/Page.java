package ca.leighnet.reviews.storage.jsonld;

import java.util.List;
import java.util.function.Function;

/**
 * The items at {@code offset} (at most {@code limit}) and the total across all pages.
 */
public record Page<T>(List<T> items, long total, int limit, int offset) {

	public <R> Page<R> map(Function<? super T, ? extends R> mapper) {
		return new Page<>(items.stream().<R>map(mapper).toList(), total, limit, offset);
	}

}
