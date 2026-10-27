import type { FetchBaseQueryError } from "@reduxjs/toolkit/query";

import { idOf } from "@/lib/format";
import type { Collection, Comment, Review } from "@/lib/types";
import { api, storage } from "./api";

export const REVIEWS_PAGE_SIZE = 10;

export interface ReviewInput {
  productId: string;
  reviewBody: string;
  ratingValue: number;
}

/** A reply to a review (`reviewId`) of the product, by its owner. */
export interface ReplyInput {
  productId: string;
  reviewId: string;
  text: string;
}

/** A reply with the ETag of the version read, for If-Match. */
export interface VersionedReply {
  reply: Comment;
  etag?: string;
}

/** A review with the ETag of the version read, for If-Match. */
export interface VersionedReview {
  review: Review;
  etag?: string;
}

function body({ reviewBody, ratingValue }: ReviewInput) {
  return {
    type: "Review",
    reviewBody: reviewBody.trim() || undefined,
    reviewRating: { type: "Rating", ratingValue, bestRating: 5, worstRating: 1 },
  };
}

/** The product's reviews (and so the reviewer's own), its aggregate rating, and its place in the list. */
function reviewed(productId: string) {
  return [
    { type: "Review" as const, id: productId },
    { type: "Product" as const, id: productId },
    { type: "Product" as const, id: "LIST" },
  ];
}

export const reviewsApi = api.injectEndpoints({
  endpoints: (build) => ({
    /** A product's reviews, newest first, a page at a time. */
    listReviews: build.infiniteQuery<Collection<Review>, string, string>({
      infiniteQueryOptions: {
        initialPageParam: "",
        getNextPageParam: (lastPage) => lastPage.view.next,
      },
      query: ({ queryArg, pageParam }) =>
        pageParam || storage(`products/${queryArg}/reviews?limit=${REVIEWS_PAGE_SIZE}`),
      providesTags: (_result, _error, productId) => [{ type: "Review", id: productId }],
    }),
    /**
     * The review this person (their Person IRI) wrote of the product, or
     * null: people review a product once, then update it. It is read on
     * its own for its ETag.
     */
    myReview: build.query<VersionedReview | null, { productId: string; author: string }>({
      async queryFn({ productId, author }, _api, _extra, baseQuery) {
        const mine = await baseQuery(
          storage(`products/${productId}/reviews?author=${encodeURIComponent(author)}&limit=1`),
        );
        if (mine.error) {
          return { error: mine.error };
        }
        const found = (mine.data as Collection<Review>).member[0];
        if (!found) {
          return { data: null };
        }
        const read = await baseQuery(storage(`reviews/${idOf(found.id)}`));
        if (read.error) {
          return { error: read.error };
        }
        return { data: { review: read.data as Review, etag: read.meta?.response?.headers.get("ETag") ?? undefined } };
      },
      providesTags: (_result, _error, { productId }) => [{ type: "Review", id: productId }],
    }),
    createReview: build.mutation<Review, ReviewInput>({
      query: (input) => ({
        url: storage(`products/${input.productId}/reviews`),
        method: "POST",
        body: body(input),
      }),
      // A 409 means they reviewed it already (in another tab, say): read that review, and its rating.
      invalidatesTags: (_result, error, { productId }) =>
        !error || error.status === 409 ? reviewed(productId) : [],
    }),
    updateReview: build.mutation<Review, ReviewInput & { id: string; etag?: string }>({
      query: ({ id, etag, ...input }) => ({
        url: storage(`reviews/${id}`),
        method: "PUT",
        headers: etag ? { "If-Match": etag } : undefined,
        body: body(input),
      }),
      // A 412 means it changed elsewhere: read it again.
      invalidatesTags: (_result, error, { productId }) =>
        error ? (isStale(error) ? [{ type: "Review", id: productId }] : []) : reviewed(productId),
    }),
    deleteReview: build.mutation<void, { id: string; productId: string; etag?: string }>({
      query: ({ id, etag }) => ({
        url: storage(`reviews/${id}`),
        method: "DELETE",
        headers: etag ? { "If-Match": etag } : undefined,
      }),
      invalidatesTags: (_result, error, { productId }) =>
        error ? (isStale(error) ? [{ type: "Review", id: productId }] : []) : reviewed(productId),
    }),
    /** A reply, read on its own for its ETag, to change or delete it. */
    reply: build.query<VersionedReply, string>({
      query: (id) => storage(`comments/${id}`),
      transformResponse: (reply: Comment, meta) => ({
        reply,
        etag: meta?.response?.headers.get("ETag") ?? undefined,
      }),
      providesTags: (_result, _error, id) => [{ type: "Reply", id }],
    }),
    /**
     * The product's owner replies, once; the reviews, which embed their
     * replies, are read again. A 409 means they replied already (in another
     * tab, say): reading the reviews again shows that reply.
     */
    addReply: build.mutation<Comment, ReplyInput>({
      query: ({ reviewId, text }) => ({
        url: storage(`reviews/${reviewId}/comments`),
        method: "POST",
        body: { type: "Comment", text: text.trim() },
      }),
      invalidatesTags: (_result, error, { productId }) =>
        !error || error.status === 409 ? [{ type: "Review", id: productId }] : [],
    }),
    updateReply: build.mutation<Comment, { id: string; productId: string; text: string; etag?: string }>({
      query: ({ id, text, etag }) => ({
        url: storage(`comments/${id}`),
        method: "PUT",
        headers: etag ? { "If-Match": etag } : undefined,
        body: { type: "Comment", text: text.trim() },
      }),
      // A 412 means it changed elsewhere: read it, and the reviews showing it, again.
      invalidatesTags: (_result, error, { id, productId }) =>
        !error || isStale(error)
          ? [
              { type: "Reply", id },
              { type: "Review", id: productId },
            ]
          : [],
    }),
    deleteReply: build.mutation<void, { id: string; productId: string; etag?: string }>({
      query: ({ id, etag }) => ({
        url: storage(`comments/${id}`),
        method: "DELETE",
        headers: etag ? { "If-Match": etag } : undefined,
      }),
      invalidatesTags: (_result, error, { id, productId }) =>
        error
          ? isStale(error)
            ? [
                { type: "Reply", id },
                { type: "Review", id: productId },
              ]
            : []
          : [{ type: "Review", id: productId }],
    }),
  }),
});

function isStale(error: FetchBaseQueryError | undefined): boolean {
  return error?.status === 412;
}

export const {
  useListReviewsInfiniteQuery,
  useMyReviewQuery,
  useCreateReviewMutation,
  useUpdateReviewMutation,
  useDeleteReviewMutation,
  useReplyQuery,
  useLazyReplyQuery,
  useAddReplyMutation,
  useUpdateReplyMutation,
  useDeleteReplyMutation,
} = reviewsApi;
