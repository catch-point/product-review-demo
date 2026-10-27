"use client";

import { useState } from "react";
import Link from "next/link";

const STORAGE_KEY = "demo-highlights-dismissed";

const highlights = [
  { title: "Optimistic Concurrency", text: "ETags + If-Match prevent lost updates with clear conflict handling." },
  { title: "Audit + Cache Sync", text: "Append-only journal with Postgres LISTEN/NOTIFY keeps caches consistent." },
  { title: "Soft Delete", text: "Discontinued products hide from discovery while preserving reviews and replies." },
  { title: "JSON-LD/Hydra API", text: "Schema.org terms with Hydra collections, paging, and search templates." },
];

function getInitialDismissed() {
  if (typeof window === "undefined") return true;
  try {
    return localStorage.getItem(STORAGE_KEY) === "true";
  } catch {
    return false;
  }
}

export function DemoHighlights() {
  const [dismissed, setDismissed] = useState(getInitialDismissed);

  const dismiss = () => {
    try {
      localStorage.setItem(STORAGE_KEY, "true");
    } catch {
      // ignore
    }
    setDismissed(true);
  };

  if (dismissed) return null;

  return (
    <div className="mb-6 rounded-lg border border-blue-100 bg-blue-50 p-4">
      <div className="mb-2 flex items-center justify-between">
        <div className="text-sm font-semibold text-blue-800">Portfolio Demo Highlights</div>
        <button type="button" onClick={dismiss} className="text-xs text-blue-600 hover:text-blue-800">
          Dismiss
        </button>
      </div>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {highlights.map((h) => (
          <div key={h.title} className="rounded bg-white/70 p-3">
            <div className="mb-1 text-xs font-semibold text-blue-700">{h.title}</div>
            <div className="text-xs text-gray-700">{h.text}</div>
          </div>
        ))}
      </div>
      <div className="mt-3 text-xs text-blue-700">
        <Link href="/about" className="font-semibold hover:underline">
          Learn more about the design →
        </Link>
      </div>
    </div>
  );
}
