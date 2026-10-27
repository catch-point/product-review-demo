import type { Metadata } from "next";
import { Suspense } from "react";

import { Spinner } from "@/components/ui";
import { readProduct } from "@/server/storage";
import { ProductDetails } from "./ProductDetails";

export async function generateMetadata({ params }: PageProps<"/products/[id]">): Promise<Metadata> {
  const product = await readProduct((await params).id);
  if (!product) {
    return { title: "Product" };
  }
  return { title: product.name };
}

export default function ProductPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <ProductDetails />
    </Suspense>
  );
}
