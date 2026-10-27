package ca.leighnet.reviews.storage.jsonld;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A page of a hydra:Collection: its members, how many there are in all, and a
 * hydra:PartialCollectionView linking to the other pages. "search" and
 * "seeAlso" (rdfs:seeAlso, a related resource) are optional.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "@context", "id", "type", "totalItems", "member", "view", "search", "seeAlso" })
public record HydraCollection<T>(@JsonProperty("@context") String context, String id, String type,
		long totalItems, List<T> member, View view, IriTemplate search, String seeAlso) {

	public static final String TYPE = "Collection";

	/** Links to the first, previous, next and last pages; absent links do not exist. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyOrder({ "id", "type" })
	public record View(String id, String type, String first, String previous, String next, String last) {

		public static final String TYPE = "PartialCollectionView";

	}

	/** An RFC 6570 template for searching the collection. */
	@JsonPropertyOrder({ "type" })
	public record IriTemplate(String type, String template, String variableRepresentation,
			List<Mapping> mapping) {

		public static final String TYPE = "IriTemplate";

		public IriTemplate(String template, List<Mapping> mapping) {
			this(TYPE, template, "BasicRepresentation", mapping);
		}

	}

	/** Binds a template variable to the property it filters on. */
	@JsonPropertyOrder({ "type" })
	public record Mapping(String type, String variable, String property, boolean required) {

		public static final String TYPE = "IriTemplateMapping";

		public Mapping(String variable, String property) {
			this(TYPE, variable, property, false);
		}

	}

}
