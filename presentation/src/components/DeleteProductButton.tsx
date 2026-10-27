"use client";

import { describeError } from "@/lib/problem";
import type { Product } from "@/lib/types";
import { idOf } from "@/lib/format";
import { useDeleteProductMutation } from "@/store/products";
import { SmallButton } from "./ui";

/**
 * Discontinues after the owner confirms. The product leaves listings and takes
 * no new reviews, but its reviews and replies stay readable.
 */
export function DeleteProductButton({
  product,
  etag,
  onDeleted,
}: {
  product: Product;
  /** The version shown, sent as If-Match. */
  etag?: string;
  onDeleted?: () => void;
}) {
  const [remove, { isLoading }] = useDeleteProductMutation();

  async function confirmAndDelete() {
    if (!window.confirm(`Discontinue ${product.name}? It will leave listings and take no new reviews.`)) {
      return;
    }
    const result = await remove({ id: idOf(product.id), etag });
    if ("error" in result) {
      window.alert(`${product.name} was not discontinued. ${describeError(result.error)}`);
    } else {
      onDeleted?.();
    }
  }

  return (
    <SmallButton type="button" tone="red" onClick={confirmAndDelete} disabled={isLoading}>
      {isLoading ? "Discontinuing…" : "Discontinue"}
    </SmallButton>
  );
}
