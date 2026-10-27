"use client";

import { useRouter } from "next/navigation";

import { RequireSession } from "@/components/RequireSession";
import { ProductForm } from "@/components/ProductForm";
import { FormCard } from "@/components/ui";
import { productPath } from "@/lib/format";
import type { ProductInput } from "@/lib/types";
import { useCreateProductMutation } from "@/store/products";

export function NewProduct() {
  return <RequireSession role="owner">{() => <Create />}</RequireSession>;
}

function Create() {
  const router = useRouter();
  const [create, { isLoading, error }] = useCreateProductMutation();

  async function submit(input: ProductInput) {
    const result = await create(input);
    if ("data" in result && result.data) {
      // The new product takes the form's place: Back goes to where the owner came from.
      router.replace(productPath(result.data.id));
    }
  }

  return (
    <FormCard title="Add product" wide>
      <ProductForm submitLabel="Create product" saving={isLoading} error={error} onSubmit={submit} />
    </FormCard>
  );
}
