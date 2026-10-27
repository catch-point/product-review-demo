"use client";

import { useId, useState, type FormEvent, type ReactNode } from "react";

import { ErrorMessage, Label, SmallButton, Spinner } from "@/components/ui";
import { formatDate, idOf } from "@/lib/format";
import { describeError, statusOf, type ApiError } from "@/lib/problem";
import type { Comment, Review } from "@/lib/types";
import {
  useAddReplyMutation,
  useDeleteReplyMutation,
  useLazyReplyQuery,
  useReplyQuery,
  useUpdateReplyMutation,
} from "@/store/reviews";

type Notice = "posted" | "updated" | "deleted";

const NOTICES: Record<Notice, string> = {
  posted: "Your reply is posted.",
  updated: "Your reply is updated.",
  deleted: "Your reply is deleted.",
};

/** A reply to a review, by the product's owner. */
export function Reply({ reply, children }: { reply: Comment; children?: ReactNode }) {
  return (
    <div className="mt-3 border-l-2 border-gray-200 pl-3 text-sm text-gray-600">
      <strong>{reply.author.name}</strong> replied on {formatDate(reply.datePublished)}
      {reply.dateModified && `, updated on ${formatDate(reply.dateModified)}`}: {reply.text}
      {children}
    </div>
  );
}

/**
 * The product's owner replies to a review, once: after that it is their
 * reply (`mine`), to edit or delete, and once deleted they may reply again.
 */
export function OwnerReply({ review, productId, mine }: { review: Review; productId: string; mine?: Comment }) {
  const [addReply, add] = useAddReplyMutation();
  const [updateReply, update] = useUpdateReplyMutation();
  const [deleteReply, remove] = useDeleteReplyMutation();
  const [readReply, read] = useLazyReplyQuery();
  const [mode, setMode] = useState<"view" | "new" | "edit">("view");
  const [notice, setNotice] = useState<Notice>();

  function clear() {
    setNotice(undefined);
    add.reset();
    update.reset();
    remove.reset();
  }

  async function post(text: string) {
    clear();
    const result = await addReply({ productId, reviewId: idOf(review.id), text });
    if ("data" in result) {
      setNotice("posted");
    }
    // Posted, or refused because they replied already: show the reply either way.
    if ("data" in result || statusOf(result.error) === 409) {
      setMode("view");
    }
  }

  async function save(text: string, etag?: string) {
    clear();
    const result = await updateReply({ id: idOf(mine!.id), productId, text, etag });
    if ("data" in result) {
      setNotice("updated");
      setMode("view");
    }
  }

  async function discard() {
    if (!mine || !window.confirm("Delete your reply? This cannot be undone.")) {
      return;
    }
    clear();
    const current = await readReply(idOf(mine.id));
    const result = await deleteReply({ id: idOf(mine.id), productId, etag: current.data?.etag });
    if (!("error" in result)) {
      setNotice("deleted");
    }
  }

  const error = errorText(add.error) ?? describeError(update.error) ?? describeError(remove.error) ?? describeError(read.error);
  if (mode === "new") {
    return (
      <ReplyFields
        review={review}
        submitLabel="Post reply"
        busy={add.isLoading}
        error={error}
        onChange={clear}
        onSubmit={post}
        onCancel={() => {
          clear();
          setMode("view");
        }}
      />
    );
  }
  if (mode === "edit" && mine) {
    return (
      <EditReply
        review={review}
        id={idOf(mine.id)}
        busy={update.isLoading}
        error={error}
        onChange={clear}
        onSubmit={save}
        onCancel={() => {
          clear();
          setMode("view");
        }}
      />
    );
  }
  const busy = remove.isLoading || read.isFetching;
  return (
    <>
      {mine && (
        <Reply reply={mine}>
          <div className="mt-2 flex justify-end gap-2">
            <SmallButton
              type="button"
              disabled={busy}
              onClick={() => {
                clear();
                setMode("edit");
              }}
            >
              Edit reply
            </SmallButton>
            <SmallButton type="button" tone="red" disabled={busy} onClick={discard}>
              Delete reply
            </SmallButton>
          </div>
        </Reply>
      )}
      <div className="mt-3 flex flex-wrap items-center justify-end gap-3">
        <ErrorMessage>{error}</ErrorMessage>
        {notice && (
          <p role="status" className="text-sm text-green-700">
            {NOTICES[notice]}
          </p>
        )}
        {!mine && (
          <SmallButton
            type="button"
            disabled={busy}
            onClick={() => {
              clear();
              setMode("new");
            }}
          >
            Reply
          </SmallButton>
        )}
      </div>
    </>
  );
}

/** A second reply (from another tab, say) brings back the first. */
function errorText(error: ApiError): string | undefined {
  return statusOf(error) === 409
    ? "You have already replied to this review. Here is your reply: edit it instead."
    : describeError(error);
}

/** Editing the reply: read on its own for its ETag, and again after a 412, which starts the fields again. */
function EditReply({
  id,
  ...props
}: { id: string; onSubmit: (text: string, etag?: string) => void } & Omit<
  Parameters<typeof ReplyFields>[0],
  "onSubmit" | "initial" | "submitLabel"
>) {
  const { data, error, isFetching } = useReplyQuery(id, { refetchOnMountOrArgChange: true });
  if (!data) {
    return error ? <ErrorMessage>{describeError(error)}</ErrorMessage> : <Spinner label="Loading your reply…" />;
  }
  return (
    <ReplyFields
      key={data.etag ?? id}
      {...props}
      initial={data.reply.text}
      submitLabel="Update reply"
      busy={props.busy || isFetching}
      onSubmit={(text) => props.onSubmit(text, data.etag)}
    />
  );
}

function ReplyFields({
  review,
  initial = "",
  submitLabel,
  busy,
  error,
  onChange,
  onSubmit,
  onCancel,
}: {
  review: Review;
  initial?: string;
  submitLabel: string;
  busy: boolean;
  error?: string;
  onChange: () => void;
  onSubmit: (text: string) => void;
  onCancel: () => void;
}) {
  const [text, setText] = useState(initial);
  const textId = useId();

  function submit(e: FormEvent) {
    e.preventDefault();
    onSubmit(text);
  }

  return (
    <form onSubmit={submit} className="mt-3 space-y-2">
      <Label htmlFor={textId}>Your reply to {review.author.name ?? "this review"}</Label>
      <textarea
        id={textId}
        name="reply"
        required
        maxLength={5000}
        autoFocus
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          onChange();
        }}
        className="h-24 w-full resize-none rounded-lg border-0 p-2 shadow-xs ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-blue-600"
        placeholder="Write your reply..."
      />
      <ErrorMessage>{error}</ErrorMessage>
      <div className="flex justify-end gap-2">
        <button
          type="button"
          onClick={onCancel}
          className="cursor-pointer px-4 py-2 text-xs uppercase text-gray-600 hover:text-gray-900"
        >
          Cancel
        </button>
        <SmallButton type="submit" disabled={busy || !text.trim()}>
          {busy ? "Saving…" : submitLabel}
        </SmallButton>
      </div>
    </form>
  );
}
