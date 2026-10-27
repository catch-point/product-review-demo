import type { Collection, CategoryList, ImageObject, Product, ProductInput } from "@/lib/types";
import { api, storage } from "./api";

export const PAGE_SIZE = 12;

/** The filters `GET /products` takes, as strings straight from the page's URL. */
export interface ProductFilters {
  category?: string;
  minRating?: string;
  maxRating?: string;
}

export interface VersionedProduct {
  product: Product;
  /** Sent back as If-Match, so a change never overwrites one the user has not seen. */
  etag?: string;
}

function firstPage(filters: ProductFilters): string {
  const params = new URLSearchParams({ limit: String(PAGE_SIZE) });
  for (const [name, value] of Object.entries(filters)) {
    if (value) {
      params.set(name, value);
    }
  }
  return storage(`products?${params}`);
}

export const productsApi = api.injectEndpoints({
  endpoints: (build) => ({
    /**
     * Pages of products, best rated first. Each page's Hydra `next` link
     * (an /api/storage URL) is the next page's parameter.
     */
    listProducts: build.infiniteQuery<Collection<Product>, ProductFilters, string>({
      infiniteQueryOptions: {
        initialPageParam: "",
        getNextPageParam: (lastPage) => lastPage.view.next,
      },
      query: ({ queryArg, pageParam }) => pageParam || firstPage(queryArg),
      providesTags: [{ type: "Product", id: "LIST" }],
      // Next.js keeps the page that was left mounted (hidden) for Back and Forward;
      // this keeps its pages for a while longer after it is dropped.
      keepUnusedDataFor: 300,
    }),
    getProduct: build.query<VersionedProduct, string>({
      query: (id) => storage(`products/${id}`),
      transformResponse: (product: Product, meta) => ({
        product,
        etag: meta?.response?.headers.get("ETag") ?? undefined,
      }),
      providesTags: (_result, _error, id) => [{ type: "Product", id }],
    }),
    categories: build.query<string[], void>({
      query: () => storage("categories"),
      transformResponse: (list: CategoryList) => list.hasDefinedTerm,
      providesTags: ["Category"],
      keepUnusedDataFor: 3600,
    }),
    createProduct: build.mutation<Product, ProductInput>({
      query: (body) => ({ url: storage("products"), method: "POST", body: { type: "Product", ...body } }),
      invalidatesTags: [{ type: "Product", id: "LIST" }, "Me"],
    }),
    updateProduct: build.mutation<Product, { id: string; etag?: string; body: ProductInput }>({
      query: ({ id, etag, body }) => ({
        url: storage(`products/${id}`),
        method: "PUT",
        headers: etag ? { "If-Match": etag } : undefined,
        body: { type: "Product", ...body },
      }),
      invalidatesTags: (_result, error, { id }) =>
        // A 412 means someone else changed it: refetch, so the user sees what they did.
        [{ type: "Product", id }, ...(error ? [] : [{ type: "Product" as const, id: "LIST" }])],
    }),
    /** Deletes the version of the product the owner is looking at. */
    deleteProduct: build.mutation<void, { id: string; etag?: string }>({
      query: ({ id, etag }) => ({
        url: storage(`products/${id}`),
        method: "DELETE",
        headers: etag ? { "If-Match": etag } : undefined,
      }),
      // "Me" lists what the owner owns. A 412 means someone else changed it: refetch it.
      invalidatesTags: (_result, error, { id }) =>
        error ? [{ type: "Product", id }] : [{ type: "Product", id: "LIST" }, "Me"],
    }),
    uploadImage: build.mutation<ImageObject, File>({
      query: (file) => {
        const form = new FormData();
        form.append("file", file);
        return { url: storage("images"), method: "POST", body: form };
      },
    }),
  }),
});

export const {
  useListProductsInfiniteQuery,
  useGetProductQuery,
  useCategoriesQuery,
  useCreateProductMutation,
  useUpdateProductMutation,
  useDeleteProductMutation,
  useUploadImageMutation,
} = productsApi;
