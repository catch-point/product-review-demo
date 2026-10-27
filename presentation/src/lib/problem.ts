import type { SerializedError } from "@reduxjs/toolkit";
import type { FetchBaseQueryError } from "@reduxjs/toolkit/query";

import type { Problem } from "./types";

export type ApiError = FetchBaseQueryError | SerializedError | undefined;

export function statusOf(error: ApiError): number | string | undefined {
  return error && "status" in error ? error.status : undefined;
}

/** A sentence for the user, from a problem document where the server sent one. */
export function describeError(error: ApiError): string | undefined {
  if (!error) {
    return undefined;
  }
  if ("status" in error) {
    const problem = error.data as Problem | undefined;
    if (error.status === 412) {
      // The mutation's tags refetch it, so the page already shows their version.
      return "Someone else changed this since you opened it. Their version is shown now: check it, then try again.";
    }
    if (error.status === 429) {
      // Rate limited (storage's reviews.rate-limits, a minute at a time).
      return "That was a lot of requests in a short time. Wait a minute, then try again.";
    }
    if (typeof problem === "object" && problem?.detail) {
      return problem.detail;
    }
    if (error.status === "FETCH_ERROR") {
      return "Could not reach the server. Check your connection and try again.";
    }
    return typeof problem === "object" && problem?.title ? problem.title : `Request failed (${error.status})`;
  }
  return error.message ?? "Something went wrong";
}
