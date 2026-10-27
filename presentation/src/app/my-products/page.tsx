import type { Metadata } from "next";
import { Suspense } from "react";

import { Spinner } from "@/components/ui";
import { MyProducts } from "./MyProducts";
import { DemoHighlights } from "@/components/DemoHighlights";

export const metadata: Metadata = { title: "My products" };

export default function MyProductsPage() {
  return (
    <div className="mx-auto max-w-6xl px-4 xl:px-0">
      <DemoHighlights />
      <Suspense fallback={<Spinner />}>
        <MyProducts />
      </Suspense>
    </div>
  );
}
