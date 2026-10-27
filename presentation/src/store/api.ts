import { createApi, fetchBaseQuery } from "@reduxjs/toolkit/query/react";

/**
 * One RTK Query API for the application's own routes: /api/auth/* (the
 * session) and /api/storage/* (the storage service, proxied). Endpoints are
 * injected per feature in ./auth.ts, ./products.ts and ./reviews.ts.
 */
export const api = createApi({
  reducerPath: "api",
  baseQuery: fetchBaseQuery({
    baseUrl: "/api/",
    credentials: "same-origin",
    // Documents are JSON-LD; RTK Query only recognises application/json by default.
    jsonContentType: "application/ld+json",
    isJsonContentType: (headers) => /[/+]json\b/.test(headers.get("content-type") ?? ""),
    prepareHeaders: (headers) => {
      if (!headers.has("Accept")) {
        headers.set("Accept", "application/ld+json, application/problem+json");
      }
      return headers;
    },
  }),
  tagTypes: ["Session", "Me", "Product", "Review", "Reply", "Category"],
  endpoints: () => ({}),
});

/** A storage path, relative to the API's base URL. */
export function storage(path: string): string {
  return `storage/${path}`;
}
