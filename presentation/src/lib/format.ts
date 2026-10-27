import type { Rating } from "./types";

/** The last path segment of a node's IRI: `…/products/12` → `12`. */
export function idOf(iri: string): string {
  return new URL(iri).pathname.split("/").pop() ?? "";
}

export function productPath(iri: string): string {
  return `/products/${idOf(iri)}`;
}

/** A review's rating on the 1-5 scale, as the storage service averages them. */
export function fiveStarValue(rating: Rating): number {
  const best = rating.bestRating ?? 5;
  const worst = rating.worstRating ?? 1;
  return 1 + (4 * (rating.ratingValue - worst)) / (best - worst);
}

export type Star = "full" | "half" | "empty";

/** Five stars for a 0-5 value, to the nearest half star. */
export function stars(value: number): Star[] {
  const halves = Math.round(Math.min(Math.max(value, 0), 5) * 2);
  return Array.from({ length: 5 }, (_, i) => (halves >= 2 * (i + 1) ? "full" : halves === 2 * i + 1 ? "half" : "empty"));
}

/** `02/22/2024`, as the site shows dates. */
export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString("en-US", { year: "numeric", month: "2-digit", day: "2-digit" });
}
