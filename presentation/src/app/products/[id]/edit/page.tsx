import type { Metadata } from "next";
import { Suspense } from "react";

import { Spinner } from "@/components/ui";
import { readProduct } from "@/server/storage";
import { EditProduct } from "./EditProduct";

export async function generateMetadata({ params }: PageProps<"/products/[id]/edit">): Promise<Metadata> {
  const product = await readProduct((await params).id);
  return { title: product ? `Edit ${product.name}` : "Edit product" };
}

export default function EditProductPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <EditProduct />
    </Suspense>
  );
}
