import type { Metadata } from "next";
import { Suspense } from "react";

import { Spinner } from "@/components/ui";
import { ProductBrowser } from "./ProductBrowser";
import { DemoHighlights } from "@/components/DemoHighlights";

export const metadata: Metadata = { title: "Products" };

export default function HomePage() {
  return (
    <div className="mx-auto max-w-6xl px-4 xl:px-0">
      <DemoHighlights />
      <Suspense fallback={<Spinner />}>
        <ProductBrowser />
      </Suspense>
    </div>
  );
}
