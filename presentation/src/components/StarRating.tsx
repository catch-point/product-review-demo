import { stars } from "@/lib/format";

/** Material Symbols stars: filled, half, and outlined. */
export function StarRating({ value, label }: { value: number | undefined; label?: string }) {
  if (value === undefined) {
    return <span className="text-sm text-gray-400">{label ?? "No reviews yet"}</span>;
  }
  return (
    <span className="select-none whitespace-nowrap" role="img" aria-label={`${value.toFixed(1)} out of 5 stars`}>
      {stars(value).map((star, i) => (
        <span
          key={i}
          aria-hidden
          className="material-symbols-outlined text-yellow-500"
          style={star === "empty" ? undefined : { fontVariationSettings: "'FILL' 1" }}
        >
          {star === "half" ? "star_rate_half" : "star_rate"}
        </span>
      ))}
    </span>
  );
}
