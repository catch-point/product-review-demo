"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useMemo } from "react";

import { DeleteProductButton } from "@/components/DeleteProductButton";
import { InfiniteScroll } from "@/components/InfiniteScroll";
import { RequireSession } from "@/components/RequireSession";
import { PLACEHOLDER_IMAGE } from "@/components/ProductCard";
import { StarRating } from "@/components/StarRating";
import { ErrorMessage, SMALL_LINK, Spinner } from "@/components/ui";
import { fiveStarValue, formatDate } from "@/lib/format";
import { describeError, statusOf } from "@/lib/problem";
import type { Session } from "@/lib/session";
import type { Review } from "@/lib/types";
import { useMeQuery } from "@/store/auth";
import { useGetProductQuery } from "@/store/products";
import { useListReviewsInfiniteQuery } from "@/store/reviews";
import { OwnerReply, Reply } from "./ReplyForm";
import { ReviewForm } from "./ReviewForm";

export function ProductDetails() {
  const { id } = useParams<{ id: string }>();
  return <RequireSession>{(session) => <Details id={id} session={session} />}</RequireSession>;
}

function Details({ id, session }: { id: string; session: Session }) {
  const router = useRouter();
  const { data, error, isLoading } = useGetProductQuery(id);
  // Who owns the product, or wrote which review.
  const { data: me } = useMeQuery();

  if (isLoading) {
    return <Spinner label="Loading product…" />;
  }
  if (error || !data) {
    return (
      <div className="mx-auto max-w-6xl px-4 py-16 xl:px-0">
        <ErrorMessage>{statusOf(error) === 404 ? "There is no such product." : describeError(error)}</ErrorMessage>
      </div>
    );
  }

  const { product, etag } = data;
  const rating = product.aggregateRating;
  const owns = !!me && product.owner === me.id;

  return (
    <div className="mx-auto max-w-6xl px-4 xl:px-0">
      <div className="mt-16 flex flex-wrap items-center gap-x-4 gap-y-2">
        <h2 className="text-3xl font-bold text-gray-800">{product.name}</h2>
        <StarRating value={rating?.ratingValue} />
        {rating && (
          <span className="text-sm text-gray-500">
            {rating.ratingValue.toFixed(1)} from {rating.ratingCount} {rating.ratingCount === 1 ? "rating" : "ratings"}
          </span>
        )}
        {owns && (
          <div className="ml-auto flex gap-2">
            <Link href={`/products/${id}/edit`} className={SMALL_LINK}>
              Edit
            </Link>
            <DeleteProductButton
              product={product}
              etag={etag}
              onDeleted={() => router.replace("/my-products")}
            />
          </div>
        )}
      </div>

      <div className="mb-4 text-sm font-semibold text-gray-500">
        {[product.brand, ...(product.category ?? [])].filter(Boolean).join(" · ")}
        {product.url && /^https?:\/\//i.test(product.url) && (
          <>
            {" · "}
            <a href={product.url} target="_blank" rel="noopener noreferrer" className="text-blue-500 hover:underline">
              Website
            </a>
          </>
        )}
      </div>

      <div className="grid grid-cols-1 gap-8 md:grid-cols-2">
        <p className="whitespace-pre-line text-justify text-gray-800">{product.description}</p>
        {/* eslint-disable-next-line @next/next/no-img-element -- images come from the storage service */}
        <img src={product.image ?? PLACEHOLDER_IMAGE} alt={product.name} className="rounded-lg shadow-lg" />
      </div>

      {(product.gtin || product.sku || product.mpn) && (
        <dl className="mt-6 flex flex-wrap gap-x-8 gap-y-2 text-sm">
          {product.gtin && (
            <div>
              <dt className="font-semibold text-gray-700">GTIN</dt>
              <dd className="text-gray-500">{product.gtin}</dd>
            </div>
          )}
          {product.sku && (
            <div>
              <dt className="font-semibold text-gray-700">SKU</dt>
              <dd className="text-gray-500">{product.sku}</dd>
            </div>
          )}
          {product.mpn && (
            <div>
              <dt className="font-semibold text-gray-700">MPN</dt>
              <dd className="text-gray-500">{product.mpn}</dd>
            </div>
          )}
        </dl>
      )}

      <section className="my-6">
        <h2 className="mb-4 text-2xl font-semibold text-gray-700">Reviews</h2>
        {session.role === "reviewer" && me && <ReviewForm productId={id} author={me.id} />}
        <Reviews productId={id} owner={owns ? me.id : undefined} />
      </section>
    </div>
  );
}

/** The product's reviews, which its owner (`owner`, their Person IRI, when they are signed in) may reply to. */
function Reviews({ productId, owner }: { productId: string; owner?: string }) {
  const { data, error, isLoading, hasNextPage, isFetchingNextPage, fetchNextPage } =
    useListReviewsInfiniteQuery(productId);
  const reviews = useMemo(() => {
    const seen = new Set<string>();
    return (data?.pages ?? []).flatMap((p) => p.member).filter((r) => !seen.has(r.id) && !!seen.add(r.id));
  }, [data]);

  if (isLoading) {
    return <Spinner label="Loading reviews…" />;
  }
  if (error) {
    return <ErrorMessage>{describeError(error)}</ErrorMessage>;
  }
  if (reviews.length === 0) {
    return <p className="my-6 text-gray-500">No reviews yet.</p>;
  }
  return (
    <div className="my-6">
      {reviews.map((review) => (
        <ReviewItem key={review.id} review={review} productId={productId} owner={owner} />
      ))}
      <InfiniteScroll
        hasMore={hasNextPage}
        loading={isFetchingNextPage}
        onLoadMore={() => void fetchNextPage()}
        label="More reviews"
      />
    </div>
  );
}

function ReviewItem({ review, productId, owner }: { review: Review; productId: string; owner?: string }) {
  const mine = owner ? review.comment?.find((c) => c.author.id === owner) : undefined;
  return (
    <article className="my-3 p-4 shadow-xs">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="leading-loose">
          <StarRating value={fiveStarValue(review.reviewRating)} />
        </div>
        <div className="text-sm leading-loose text-gray-600">
          By <strong>{review.author.name ?? "a former member"}</strong> on {formatDate(review.datePublished)}
        </div>
      </div>
      {review.reviewBody && <p className="whitespace-pre-line">{review.reviewBody}</p>}
      {review.comment?.filter((c) => c !== mine).map((c) => <Reply key={c.id} reply={c} />)}
      {owner && <OwnerReply review={review} productId={productId} mine={mine} />}
    </article>
  );
}
