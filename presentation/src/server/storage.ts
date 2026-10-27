import "server-only";

import { headers as requestHeaders } from "next/headers";
import { NextResponse, type NextRequest } from "next/server";

import type { Product } from "@/lib/types";
import { clientAddressOf } from "./client-address";
import { serverConfig } from "./config";
import { problem, publicOrigin } from "./http";

/** Where the browser reaches the storage service. Node IRIs are built under it. */
export const STORAGE_PREFIX = "/api/storage";

const REQUEST_HEADERS = [
  "accept",
  "content-type",
  "if-match",
  "if-none-match",
  "if-modified-since",
  "if-unmodified-since",
];

const RESPONSE_HEADERS = [
  "content-type",
  "etag",
  "last-modified",
  "cache-control",
  "location",
  "content-location",
  "vary",
  "retry-after",
  // An image is the uploaded bytes. These keep a browser that opens its URL
  // from treating those bytes as a document on this origin (ImageController,
  // and Spring Security's nosniff).
  "content-disposition",
  "content-security-policy",
  "x-content-type-options",
];

/**
 * The largest request bodies storage accepts: JSON documents
 * (its reviews.requests.max-body-size), and image uploads (reviews.images.max-size,
 * with their multipart encoding). Larger ones are refused here, unsent.
 */
export const MAX_BODY_BYTES = 64 * 1024;
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
const MAX_UPLOAD_BYTES = MAX_IMAGE_BYTES + 64 * 1024;

/** The limit on a request body to `path` (relative to the storage root). */
export function bodyLimit(path: string): number {
  return path === "images" ? MAX_UPLOAD_BYTES : MAX_BODY_BYTES;
}

/** 413, as storage says it. */
export function bodyTooLarge(path: string): NextResponse {
  const detail =
    path === "images"
      ? `Images may be at most ${MAX_IMAGE_BYTES / 1024} KB`
      : `Request bodies may be at most ${MAX_BODY_BYTES / 1024} KB`;
  return problem(413, "Content Too Large", detail);
}

/** Whether the request says (by its Content-Length) that its body is larger than `limit`. */
export function declaresMoreThan(request: Request, limit: number): boolean {
  return Number(request.headers.get("content-length")) > limit;
}

/**
 * The request's body, or null as soon as it passes `limit` bytes: a body
 * without a Content-Length (chunked) is never read further.
 */
export async function readBody(request: Request, limit: number): Promise<ArrayBuffer | null> {
  if (declaresMoreThan(request, limit)) {
    return null;
  }
  if (!request.body) {
    return new ArrayBuffer(0);
  }
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    size += value.byteLength;
    if (size > limit) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const body = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return body.buffer;
}

export interface StorageCall {
  method: string;
  /** Relative to the storage root, without a leading slash: `products/1`. */
  path: string;
  search?: string;
  headers?: Headers;
  body?: BodyInit | null;
  token?: string;
}

/**
 * Calls the storage service as the browser's request would reach it, so the
 * IRIs in its responses (built from X-Forwarded-*) name this application's
 * /api/storage URLs, and Hydra links and images work from the browser as is.
 * X-Forwarded-For names the client, whose requests storage counts against
 * their own rate limits, not this server's.
 */
export async function callStorage(request: NextRequest, call: StorageCall): Promise<Response> {
  const origin = publicOrigin(request);
  const headers = new Headers();
  for (const name of REQUEST_HEADERS) {
    const value = call.headers?.get(name);
    if (value) {
      headers.set(name, value);
    }
  }
  headers.set("X-Forwarded-Proto", origin.protocol.replace(/:$/, ""));
  headers.set("X-Forwarded-Host", origin.host);
  headers.set("X-Forwarded-Prefix", STORAGE_PREFIX);
  const address = clientAddressOf(request.headers);
  if (address) {
    headers.set("X-Forwarded-For", address);
  }
  if (call.token) {
    headers.set("Authorization", `Bearer ${call.token}`);
  }
  try {
    return await fetch(`${serverConfig().storageUrl}/${call.path}${call.search ?? ""}`, {
      method: call.method,
      headers,
      body: call.body,
      cache: "no-store",
      redirect: "manual",
    });
  } catch {
    return problem(502, "Storage unavailable", "The storage service could not be reached");
  }
}

/** The storage response, with only the headers a browser needs. */
export function relay(upstream: Response): NextResponse {
  const headers = new Headers();
  for (const name of RESPONSE_HEADERS) {
    const value = upstream.headers.get(name);
    if (value) {
      headers.set(name, value);
    }
  }
  // The browser authenticates with its session cookie, not an Authorization
  // header, so responses that differ per caller vary by Cookie here.
  const vary = headers.get("vary");
  if (vary && /authorization/i.test(vary)) {
    headers.set("vary", vary.replace(/authorization/gi, "Cookie"));
  }
  const empty = upstream.status === 204 || upstream.status === 304;
  return new NextResponse(empty ? null : upstream.body, { status: upstream.status, headers });
}

/**
 * A product as anyone may read it, for the server's own use (its page's
 * title), or null when there is none or storage cannot say in time. Read for
 * the page's client, so it counts against their rate limit.
 */
export async function readProduct(id: string): Promise<Product | null> {
  if (!/^\d+$/.test(id)) {
    return null;
  }
  const address = await pageClientAddress();
  try {
    const response = await fetch(`${serverConfig().storageUrl}/products/${id}`, {
      headers: { Accept: "application/ld+json", ...(address ? { "X-Forwarded-For": address } : {}) },
      cache: "no-store",
      signal: AbortSignal.timeout(3000),
    });
    return response.ok ? ((await response.json()) as Product) : null;
  } catch {
    return null;
  }
}

/** The client of the page being rendered, if this is rendering one. */
async function pageClientAddress(): Promise<string | undefined> {
  try {
    return clientAddressOf(new Headers(await requestHeaders()));
  } catch {
    return undefined;
  }
}
