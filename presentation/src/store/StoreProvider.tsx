"use client";

import { useState, type ReactNode } from "react";
import { Provider } from "react-redux";

import { makeStore } from "./store";

/**
 * Lives in the root layout, so the store (and RTK Query's cache) survives
 * client-side navigation between pages, including Back and Forward.
 */
export function StoreProvider({ children }: { children: ReactNode }) {
  const [store] = useState(makeStore);
  return <Provider store={store}>{children}</Provider>;
}
