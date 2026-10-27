"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useMemo } from "react";

import { InfiniteScroll } from "@/components/InfiniteScroll";
import { RequireSession } from "@/components/RequireSession";
import { ProductCard, ProductGrid } from "@/components/ProductCard";
import { ErrorMessage, Select, Spinner } from "@/components/ui";
import { describeError } from "@/lib/problem";
import { useCategoriesQuery, useListProductsInfiniteQuery, type ProductFilters } from "@/store/products";

const RATINGS = ["5", "4", "3", "2", "1"];

/** The filters live in the URL (`/?category=Audio&minRating=4`), so Back, reload and links keep them. */
const FILTERS = ["category", "minRating", "maxRating"] as const;

export function ProductBrowser() {
  return <RequireSession role="reviewer">{() => <Browser />}</RequireSession>;
}

function Browser() {
  const params = useSearchParams();
  const filters = useMemo(() => {
    const f: ProductFilters = {};
    for (const name of FILTERS) {
      const value = params.get(name)?.trim();
      if (value) {
        f[name] = value;
      }
    }
    return f;
  }, [params]);

  const { data, error, isLoading, isFetching, hasNextPage, isFetchingNextPage, fetchNextPage } =
    useListProductsInfiniteQuery(filters);

  // Offset pages can overlap when ratings change between requests.
  const products = useMemo(() => {
    const seen = new Set<string>();
    return (data?.pages ?? []).flatMap((page) => page.member).filter((r) => !seen.has(r.id) && !!seen.add(r.id));
  }, [data]);
  const total = data?.pages.at(-1)?.totalItems;


  return (
    <div className="mx-auto w-full max-w-6xl px-4 xl:px-0">
      <FilterBar filters={filters} />

      {error ? (
        <ErrorMessage>{describeError(error)}</ErrorMessage>
      ) : isLoading ? (
        <Spinner label="Loading products…" />
      ) : products.length === 0 ? (
        <p className="py-10 text-center text-gray-500">No products match these filters.</p>
      ) : (
        <>
          <p className="mb-4 text-sm text-gray-500" aria-live="polite">
            {total === 1 ? "1 product" : `${total} products`}, best rated first
            {isFetching && !isFetchingNextPage ? " · updating…" : ""}
          </p>
          <ProductGrid>
            {products.map((r) => (
              <ProductCard key={r.id} product={r} />
            ))}
          </ProductGrid>
          <InfiniteScroll
            hasMore={hasNextPage}
            loading={isFetchingNextPage}
            onLoadMore={() => void fetchNextPage()}
            label="Load more products"
          />
        </>
      )}
    </div>
  );
}

function FilterBar({ filters }: { filters: ProductFilters }) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const { data: categories = [] } = useCategoriesQuery();

  /** Filtering replaces the history entry: Back leaves the list, not the last filter. */
  function setFilter(name: (typeof FILTERS)[number], value: string) {
    const next = new URLSearchParams(params);
    if (value) {
      next.set(name, value);
    } else {
      next.delete(name);
    }
    const query = next.toString();
    router.replace(query ? `${pathname}?${query}` : pathname, { scroll: false });
  }

  const inverted =
    filters.minRating && filters.maxRating && Number(filters.minRating) > Number(filters.maxRating);

  return (
    <div className="my-10">
      <div className="flex flex-wrap items-center justify-center gap-x-6 gap-y-3">
        <label className="flex items-center gap-1 text-sm text-gray-500">
          Category
          <Select value={filters.category ?? ""} onChange={(e) => setFilter("category", e.target.value)}>
            <option value="">Any</option>
            {categories.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </Select>
        </label>

        <label className="flex items-center gap-1 text-sm text-gray-500">
          Minimum rating
          <Select value={filters.minRating ?? ""} onChange={(e) => setFilter("minRating", e.target.value)}>
            <option value="">Any</option>
            {RATINGS.map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </Select>
        </label>

        <label className="flex items-center gap-1 text-sm text-gray-500">
          Maximum rating
          <Select value={filters.maxRating ?? ""} onChange={(e) => setFilter("maxRating", e.target.value)}>
            <option value="">Any</option>
            {RATINGS.map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </Select>
        </label>
      </div>
      {inverted && (
        <p className="mt-3 text-center text-sm text-amber-700">The minimum rating is above the maximum.</p>
      )}
    </div>
  );
}
