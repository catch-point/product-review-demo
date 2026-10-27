"use client";

import Link from "next/link";
import { useState } from "react";

import { DeleteProductButton } from "@/components/DeleteProductButton";
import { InfiniteScroll } from "@/components/InfiniteScroll";
import { RequireSession } from "@/components/RequireSession";
import { ProductCard, ProductGrid } from "@/components/ProductCard";
import { ErrorMessage, SMALL_LINK, Spinner } from "@/components/ui";
import { idOf } from "@/lib/format";
import { describeError } from "@/lib/problem";
import { useMeQuery } from "@/store/auth";
import { PAGE_SIZE, useGetProductQuery } from "@/store/products";

export function MyProducts() {
  return <RequireSession role="owner">{() => <OwnedProducts />}</RequireSession>;
}

/**
 * The products in the owner's Person (`GET /people/me`, its `owns`), newest
 * first. Each is read on its own as it scrolls into the list: those reads are
 * cached and revalidated with their ETags, which edits and deletes then send.
 */
function OwnedProducts() {
  const { data: me, error } = useMeQuery();
  const [shown, setShown] = useState(PAGE_SIZE);
  const owned = [...(me?.owns ?? [])].reverse();

  return (
    <div className="mx-auto w-full max-w-6xl px-4 xl:px-0">
      <div className="my-10 flex flex-wrap items-center justify-between gap-4">
        <h2 className="text-2xl font-semibold text-gray-700">My products</h2>
        <Link href="/products/new" className={SMALL_LINK}>
          Add product
        </Link>
      </div>

      {error ? (
        <ErrorMessage>{describeError(error)}</ErrorMessage>
      ) : !me ? (
        <Spinner label="Loading your products…" />
      ) : owned.length === 0 ? (
        <div className="py-10 text-center text-gray-500">
          <p>You have not added a product yet.</p>
          <Link href="/products/new" className="mt-2 inline-block font-semibold text-blue-600 hover:text-blue-500">
            Add your first product
          </Link>
        </div>
      ) : (
        <>
          <ProductGrid>
            {owned.slice(0, shown).map((iri) => (
              <OwnedProduct key={iri} id={idOf(iri)} />
            ))}
          </ProductGrid>
          <InfiniteScroll
            hasMore={shown < owned.length}
            loading={false}
            onLoadMore={() => setShown((n) => n + PAGE_SIZE)}
          />
        </>
      )}
    </div>
  );
}

function OwnedProduct({ id }: { id: string }) {
  const { data, error } = useGetProductQuery(id);
  if (error) {
    return <ErrorMessage>{describeError(error)}</ErrorMessage>;
  }
  if (!data) {
    return <div className="aspect-[4/3] animate-pulse rounded-lg bg-gray-100" aria-label="Loading product" />;
  }
  return (
    <ProductCard
      product={data.product}
      actions={
        <>
          <Link href={`/products/${id}/edit`} className={SMALL_LINK}>
            Edit
          </Link>
          <DeleteProductButton product={data.product} etag={data.etag} />
        </>
      }
    />
  );
}
