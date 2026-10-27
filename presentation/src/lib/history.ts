"use client";

import { usePathname } from "next/navigation";
import { useEffect, useRef } from "react";

/** The page shown before this one in this tab, if the user got here inside the application. */
let previousPath: string | null = null;

/** Mounted once, in the root layout. */
export function RouteTracker(): null {
  const pathname = usePathname();
  const current = useRef<string | null>(null);
  useEffect(() => {
    if (current.current !== pathname) {
      previousPath = current.current;
      current.current = pathname;
    }
  }, [pathname]);
  return null;
}

/** Whether the user came to this page from another page of the application. */
export function cameFromApp(): boolean {
  return previousPath !== null;
}
