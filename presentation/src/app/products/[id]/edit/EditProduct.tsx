"use client";

import { useParams, useRouter } from "next/navigation";

import { RequireSession } from "@/components/RequireSession";
import { ProductForm } from "@/components/ProductForm";
import { ErrorMessage, FormCard, Spinner } from "@/components/ui";
import { cameFromApp } from "@/lib/history";
import { describeError, statusOf } from "@/lib/problem";
import type { ProductInput } from "@/lib/types";
import { useMeQuery } from "@/store/auth";
import { useGetProductQuery, useUpdateProductMutation } from "@/store/products";

export function EditProduct() {
  const { id } = useParams<{ id: string }>();
  return <RequireSession role="owner">{() => <Edit id={id} />}</RequireSession>;
}

function Edit({ id }: { id: string }) {
  const router = useRouter();
  const { data, error, isLoading } = useGetProductQuery(id);
  const { data: me } = useMeQuery();
  const [update, { isLoading: saving, error: saveError }] = useUpdateProductMutation();

  if (isLoading || !me) {
    return <Spinner />;
  }
  if (error || !data) {
    return (
      <div className="mx-auto max-w-2xl px-4 py-16">
        <ErrorMessage>{statusOf(error) === 404 ? "There is no such product." : describeError(error)}</ErrorMessage>
      </div>
    );
  }
  if (data.product.owner !== me.id) {
    return (
      <div className="mx-auto max-w-2xl px-4 py-16">
        <ErrorMessage>Only the product&apos;s owner can edit it.</ErrorMessage>
      </div>
    );
  }

  async function submit(input: ProductInput) {
    const result = await update({ id, etag: data?.etag, body: input });
    if ("data" in result) {
      // Back to where the owner opened the form (the product or their list), as
      // if they had never left it. Opened directly, the product replaces the form.
      if (cameFromApp()) {
        router.back();
      } else {
        router.replace(`/products/${id}`);
      }
    }
  }

  return (
    <FormCard title={`Edit ${data.product.name}`} wide>
      {/* Remounts with the latest version after a 412, so the owner edits what is there now. */}
      <ProductForm
        key={data.etag}
        product={data.product}
        submitLabel="Save changes"
        saving={saving}
        error={saveError}
        onSubmit={submit}
      />
    </FormCard>
  );
}
