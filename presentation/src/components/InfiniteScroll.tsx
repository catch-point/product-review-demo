"use client";

import { useEffect, useRef } from "react";

import { Spinner } from "./ui";

/**
 * The infinite scroll trigger: loads the next page when it comes within a
 * screen of the viewport. A button stays as a fallback (keyboard users, and
 * pages too short to scroll).
 */
export function InfiniteScroll({
  hasMore,
  loading,
  onLoadMore,
  label = "Load more",
}: {
  hasMore: boolean;
  loading: boolean;
  onLoadMore: () => void;
  label?: string;
}) {
  const sentinel = useRef<HTMLDivElement>(null);
  const load = useRef(onLoadMore);
  useEffect(() => {
    load.current = onLoadMore;
  });

  useEffect(() => {
    const element = sentinel.current;
    if (!element || !hasMore || loading) {
      return;
    }
    const observer = new IntersectionObserver((entries) => entries[0]?.isIntersecting && load.current(), {
      rootMargin: "100% 0px",
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [hasMore, loading]);

  if (!hasMore) {
    return null;
  }
  return (
    <div ref={sentinel} className="flex justify-center py-6">
      {loading ? (
        <Spinner />
      ) : (
        <button type="button" onClick={onLoadMore} className="cursor-pointer text-sm font-semibold text-blue-500">
          {label}
        </button>
      )}
    </div>
  );
}
