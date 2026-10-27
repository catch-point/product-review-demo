import Link from "next/link";
import type { ReactNode } from "react";

import { productPath } from "@/lib/format";
import type { Product } from "@/lib/types";
import { StarRating } from "./StarRating";
import { SMALL_LINK } from "./ui";

export const PLACEHOLDER_IMAGE = "/product-placeholder.jpg";

/** The card in the listing: brand, categories and the review count. */
export function ProductCard({ product, actions }: { product: Product; actions?: ReactNode }) {
  const href = productPath(product.id);
  const rating = product.aggregateRating;
  const details = [product.brand, ...(product.category ?? [])].filter(Boolean);

  return (
    <article className="flex flex-col overflow-hidden rounded-lg bg-white shadow-xl transition-all duration-500 hover:-translate-y-1 hover:shadow-2xl">
      <Link href={href} className="block aspect-[3/2] overflow-hidden bg-gray-100">
        {/* eslint-disable-next-line @next/next/no-img-element -- images come from the storage service */}
        <img
          src={product.image ?? PLACEHOLDER_IMAGE}
          alt={product.name}
          loading="lazy"
          className="size-full object-cover"
        />
      </Link>
      <div className="flex flex-1 flex-col p-2">
        <h2 className="mb-1 mt-1 px-2 text-lg font-semibold leading-none text-gray-600">
          <Link href={href} className="hover:text-gray-800">
            {product.name}
          </Link>
        </h2>
        {details.length > 0 && <p className="px-2 text-xs text-gray-400">{details.join(" · ")}</p>}
        {product.description && (
          <p className="mt-1 line-clamp-2 px-2 text-sm leading-tight text-gray-500">{product.description}</p>
        )}
        <div className="mt-auto flex flex-wrap items-center justify-between gap-2 px-2 pt-5">
          <div className="flex items-center gap-1">
            <StarRating value={rating?.ratingValue} />
            {rating && <span className="text-xs text-gray-400">({rating.ratingCount})</span>}
          </div>
          <div className="flex gap-2">
            {actions}
            <Link href={href} className={SMALL_LINK}>
              Details
            </Link>
          </div>
        </div>
      </div>
    </article>
  );
}

export function ProductGrid({ children }: { children: ReactNode }) {
  return <div className="grid w-full grid-cols-1 gap-8 sm:grid-cols-2 lg:grid-cols-3">{children}</div>;
}
