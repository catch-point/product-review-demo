"use client";

import { useId, useState, type FormEvent } from "react";

import { ErrorMessage, Label, PrimaryButton, Select, SmallButton, Spinner } from "@/components/ui";
import { fiveStarValue, formatDate, idOf } from "@/lib/format";
import { describeError, statusOf, type ApiError } from "@/lib/problem";
import type { Review } from "@/lib/types";
import {
  useCreateReviewMutation,
  useDeleteReviewMutation,
  useMyReviewQuery,
  useUpdateReviewMutation,
  type VersionedReview,
} from "@/store/reviews";

type Notice = "posted" | "updated" | "deleted";

const NOTICES: Record<Notice, string> = {
  posted: "Thank you! Your review is posted.",
  updated: "Your review is updated.",
  deleted: "Your review is deleted.",
};

/**
 * The review form on a product's page. People review a product once:
 * once they have, it is their review, to update or delete.
 */
export function ReviewForm({ productId, author }: { productId: string; author: string }) {
  const { data: mine, error: loadError, isLoading, isFetching } = useMyReviewQuery({ productId, author });
  const [createReview, create] = useCreateReviewMutation();
  const [updateReview, update] = useUpdateReviewMutation();
  const [deleteReview, remove] = useDeleteReviewMutation();
  const [notice, setNotice] = useState<Notice>();

  function clear() {
    setNotice(undefined);
    create.reset();
    update.reset();
    remove.reset();
  }

  async function save(reviewBody: string, ratingValue: number) {
    clear();
    const input = { productId, reviewBody, ratingValue };
    const result = mine
      ? await updateReview({ ...input, id: idOf(mine.review.id), etag: mine.etag })
      : await createReview(input);
    if ("data" in result) {
      setNotice(mine ? "updated" : "posted");
    }
  }

  async function discard() {
    if (!mine || !window.confirm("Delete your review? This cannot be undone.")) {
      return;
    }
    clear();
    const result = await deleteReview({ id: idOf(mine.review.id), productId, etag: mine.etag });
    if (!("error" in result)) {
      setNotice("deleted");
    }
  }

  if (isLoading) {
    return <Spinner label="Loading your review…" />;
  }
  if (loadError) {
    return <ErrorMessage>{describeError(loadError)}</ErrorMessage>;
  }
  return (
    <ReviewFields
      // A new version (saved here or elsewhere) starts the fields again from it.
      key={mine?.etag ?? mine?.review.id ?? "new"}
      mine={mine ?? undefined}
      busy={create.isLoading || update.isLoading || remove.isLoading || isFetching}
      error={errorText(create.error) ?? describeError(update.error) ?? describeError(remove.error)}
      notice={notice && NOTICES[notice]}
      onChange={clear}
      onSave={save}
      onDelete={discard}
    />
  );
}

/** A second review of the product (from another tab, say) brings back the first. */
function errorText(error: ApiError): string | undefined {
  return statusOf(error) === 409
    ? "You have already reviewed this product. Here is your review: update it instead."
    : describeError(error);
}

function ReviewFields({
  mine,
  busy,
  error,
  notice,
  onChange,
  onSave,
  onDelete,
}: {
  mine?: VersionedReview;
  busy: boolean;
  error?: string;
  notice?: string;
  onChange: () => void;
  onSave: (reviewBody: string, ratingValue: number) => void;
  onDelete: () => void;
}) {
  const review = mine?.review;
  const [reviewBody, setReviewBody] = useState(review?.reviewBody ?? "");
  const [rating, setRating] = useState(review ? Math.round(fiveStarValue(review.reviewRating)) : 5);
  const commentId = useId();
  const ratingId = useId();

  function submit(e: FormEvent) {
    e.preventDefault();
    onSave(reviewBody, rating);
  }

  return (
    <form onSubmit={submit} className="space-y-3 rounded-lg px-4 py-6 shadow-md">
      <div>
        <Label htmlFor={commentId}>{review ? "Your review" : "Have you used this product? What did you think?"}</Label>
        {review && <Posted review={review} />}
        <textarea
          id={commentId}
          name="comment"
          maxLength={10000}
          value={reviewBody}
          onChange={(e) => {
            setReviewBody(e.target.value);
            onChange();
          }}
          className="mt-1 h-36 w-full resize-none rounded-lg border-0 p-2 shadow-xs ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-blue-600"
          placeholder="Write your review..."
        />
      </div>

      <div className="flex items-center gap-2">
        <Label htmlFor={ratingId}>Rating</Label>
        <Select
          id={ratingId}
          name="rating"
          className="w-20"
          value={rating}
          onChange={(e) => {
            setRating(Number(e.target.value));
            onChange();
          }}
        >
          {[5, 4, 3, 2, 1].map((r) => (
            <option key={r} value={r}>
              {r}
            </option>
          ))}
        </Select>
      </div>

      <ErrorMessage>{error}</ErrorMessage>
      {notice && (
        <p role="status" className="text-sm text-green-700">
          {notice}
        </p>
      )}
      <PrimaryButton type="submit" className="mt-4" disabled={busy}>
        {busy ? "Saving…" : review ? "Update review" : "Submit review"}
      </PrimaryButton>
      {review && (
        <div className="flex justify-end">
          <SmallButton type="button" tone="red" onClick={onDelete} disabled={busy}>
            Delete review
          </SmallButton>
        </div>
      )}
    </form>
  );
}

function Posted({ review }: { review: Review }) {
  return (
    <p className="text-sm text-gray-500">
      Posted on {formatDate(review.datePublished)}
      {review.dateModified && `, updated on ${formatDate(review.dateModified)}`}
    </p>
  );
}
