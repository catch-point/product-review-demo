/**
 * The storage service's documents (storage/src/main/resources/openapi.yaml),
 * read as plain JSON: they are always compact JSON-LD in one known context.
 * `id`s are IRIs under /api/storage.
 */

export interface Collection<T> {
  id: string;
  type: "Collection";
  totalItems: number;
  member: T[];
  view: { id: string; first: string; previous?: string; next?: string; last: string };
}

export interface AggregateRating {
  ratingValue: number;
  bestRating: number;
  worstRating: number;
  ratingCount: number;
  reviewCount: number;
}

export interface Product {
  id: string;
  type: "Product";
  name: string;
  description?: string;
  image?: string;
  url?: string;
  sameAs?: string[];
  category?: string[];
  brand?: string;
  gtin?: string;
  sku?: string;
  mpn?: string;
  owner?: string;
  aggregateRating?: AggregateRating;
  dateCreated: string;
  dateModified?: string;
}

/** What a client writes: the product without its server-assigned properties. */
export type ProductInput = Pick<
  Product,
  "name" | "description" | "image" | "url" | "sameAs" | "category" | "brand" | "gtin" | "sku" | "mpn"
>;

export interface Rating {
  ratingValue: number;
  bestRating?: number;
  worstRating?: number;
}

export interface Author {
  id: string;
  type: "Person";
  name?: string;
}

/** A reply to a review, by the product's owner. */
export interface Comment {
  id: string;
  type: "Comment";
  text: string;
  author: Author;
  datePublished: string;
  dateModified?: string;
}

export interface Review {
  id: string;
  type: "Review";
  itemReviewed: string;
  author: Author;
  reviewBody?: string;
  reviewRating: Rating;
  datePublished: string;
  dateModified?: string;
  /** The product owner's reply, if any. */
  comment?: Comment[];
}

export interface Person {
  id: string;
  type: "Person";
  name: string;
  /** Only in /people/me, the caller's own. */
  email?: string;
  owns?: string[];
}

export interface CategoryList {
  hasDefinedTerm: string[];
}

export interface ImageObject {
  id: string;
  contentUrl: string;
  width: number;
  height: number;
}

/** RFC 9457 problem details. */
export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
}
