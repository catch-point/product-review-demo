"use client";

import { useId, useState, type FormEvent } from "react";

import { describeError, type ApiError } from "@/lib/problem";
import type { Product, ProductInput } from "@/lib/types";
import { useCategoriesQuery, useUploadImageMutation } from "@/store/products";
import { PLACEHOLDER_IMAGE } from "./ProductCard";
import { ErrorMessage, Label, PrimaryButton, TextArea, TextField } from "./ui";

const IMAGE_TYPES = "image/jpeg,image/png,image/gif,image/webp";
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

interface Values {
  name: string;
  description: string;
  category: string[];
  brand: string;
  gtin: string;
  sku: string;
  mpn: string;
  url: string;
}

function valuesOf(r: Product | undefined): Values {
  return {
    name: r?.name ?? "",
    description: r?.description ?? "",
    category: r?.category ?? [],
    brand: r?.brand ?? "",
    gtin: r?.gtin ?? "",
    sku: r?.sku ?? "",
    mpn: r?.mpn ?? "",
    url: r?.url ?? "",
  };
}

function blankToUndefined(value: string): string | undefined {
  return value.trim() || undefined;
}

/**
 * The form for adding or changing a product, with the categories the
 * listing's filters use. A PUT replaces the whole product, so editing keeps
 * what the form does not show (sameAs).
 */
export function ProductForm({
  product,
  submitLabel,
  saving,
  error,
  onSubmit,
}: {
  product?: Product;
  submitLabel: string;
  saving: boolean;
  error: ApiError;
  onSubmit: (input: ProductInput) => void;
}) {
  const imageId = useId();
  const { data: categories = [] } = useCategoriesQuery();
  const [upload, { isLoading: uploading, error: uploadError }] = useUploadImageMutation();
  const [values, setValues] = useState(() => valuesOf(product));
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<string | undefined>(product?.image);
  const [fileProblem, setFileProblem] = useState<string>();

  const set = (name: keyof Values) => (e: { target: { value: string } }) =>
    setValues((v) => ({ ...v, [name]: e.target.value }));

  function toggleCategory(category: string) {
    setValues((v) => ({
      ...v,
      category: v.category.includes(category) ? v.category.filter((c) => c !== category) : [...v.category, category],
    }));
  }

  function chooseFile(chosen: File | undefined) {
    setFileProblem(undefined);
    if (preview?.startsWith("blob:")) {
      URL.revokeObjectURL(preview);
    }
    if (!chosen) {
      setFile(null);
      setPreview(product?.image);
      return;
    }
    if (chosen.size > MAX_IMAGE_BYTES) {
      setFileProblem("Choose an image of 5 MB or less.");
      return;
    }
    setFile(chosen);
    setPreview(URL.createObjectURL(chosen));
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    let image = product?.image;
    if (file) {
      const uploaded = await upload(file);
      if (!("data" in uploaded) || !uploaded.data) {
        return;
      }
      image = uploaded.data.id;
    }
    onSubmit({
      name: values.name.trim(),
      description: blankToUndefined(values.description),
      url: blankToUndefined(values.url),
      image,
      category: values.category,
      brand: blankToUndefined(values.brand),
      gtin: blankToUndefined(values.gtin),
      sku: blankToUndefined(values.sku),
      mpn: blankToUndefined(values.mpn),
      sameAs: product?.sameAs,
    });
  }

  const busy = saving || uploading;

  return (
    <form className="space-y-6" onSubmit={submit}>
      <TextField label="Product name" name="name" required maxLength={200} value={values.name} onChange={set("name")} />

      <TextArea
        label="Description"
        name="description"
        required
        maxLength={5000}
        className="[&_textarea]:h-48"
        value={values.description}
        onChange={set("description")}
      />

      <fieldset>
        <legend className="block text-sm font-semibold text-gray-800">Categories</legend>
        <div className="mt-2 flex flex-wrap gap-x-5 gap-y-2">
          {categories.map((c) => (
            <label key={c} className="flex items-center gap-1.5 text-sm text-gray-700">
              <input
                type="checkbox"
                checked={values.category.includes(c)}
                onChange={() => toggleCategory(c)}
                className="size-4 accent-blue-600"
              />
              {c}
            </label>
          ))}
        </div>
      </fieldset>

      <fieldset className="grid grid-cols-1 gap-4 sm:grid-cols-6">
        <legend className="mb-2 block text-sm font-semibold text-gray-800">Product details</legend>
        <TextField
          label="Brand"
          name="brand"
          maxLength={200}
          className="sm:col-span-3"
          value={values.brand}
          onChange={set("brand")}
        />
        <TextField
          label="GTIN"
          name="gtin"
          inputMode="numeric"
          pattern="\d{8}|\d{12,14}"
          maxLength={14}
          placeholder="8 or 12-14 digits"
          className="sm:col-span-3"
          value={values.gtin}
          onChange={set("gtin")}
        />
        <TextField
          label="SKU"
          name="sku"
          maxLength={100}
          className="sm:col-span-3"
          value={values.sku}
          onChange={set("sku")}
        />
        <TextField
          label="MPN"
          name="mpn"
          maxLength={100}
          className="sm:col-span-3"
          value={values.mpn}
          onChange={set("mpn")}
        />
      </fieldset>

      <TextField
        label="Website"
        name="url"
        type="url"
        placeholder="https://"
        value={values.url}
        onChange={set("url")}
      />

      <div>
        <Label htmlFor={imageId}>Image</Label>
        <div className="mt-2 flex flex-wrap items-center gap-4">
          {/* eslint-disable-next-line @next/next/no-img-element -- a local preview or a storage image */}
          <img src={preview ?? PLACEHOLDER_IMAGE} alt="" className="h-24 w-36 rounded-md object-cover shadow-xs" />
          <input
            id={imageId}
            name="image"
            type="file"
            accept={IMAGE_TYPES}
            onChange={(e) => chooseFile(e.target.files?.[0])}
            className="text-sm file:mr-3 file:cursor-pointer file:rounded-md file:border-0 file:bg-gray-100 file:px-3 file:py-1.5 file:text-sm file:font-semibold"
          />
        </div>
        <p className="mt-1 text-xs text-gray-500">JPEG, PNG, GIF or WebP, up to 5 MB.</p>
      </div>

      <ErrorMessage>{fileProblem ?? describeError(uploadError) ?? describeError(error)}</ErrorMessage>

      <PrimaryButton type="submit" disabled={busy}>
        {uploading ? "Uploading image…" : saving ? "Saving…" : submitLabel}
      </PrimaryButton>
    </form>
  );
}
