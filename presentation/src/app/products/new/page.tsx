import type { Metadata } from "next";
import { Suspense } from "react";

import { Spinner } from "@/components/ui";
import { NewProduct } from "./NewProduct";
import { DemoHighlights } from "@/components/DemoHighlights";

export const metadata: Metadata = { title: "Add product" };

export default function NewProductPage() {
  return (
    <div className="mx-auto max-w-6xl px-4 xl:px-0">
      <DemoHighlights />
      <Suspense fallback={<Spinner />}>
        <NewProduct />
      </Suspense>
    </div>
  );
}
