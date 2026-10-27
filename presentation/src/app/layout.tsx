import type { Metadata } from "next";
import { Noto_Sans } from "next/font/google";
import localFont from "next/font/local";
import Link from "next/link";
import { connection } from "next/server";
import { Suspense } from "react";

import { Footer } from "@/components/Footer";
import { NavBar } from "@/components/NavBar";
import { Spinner } from "@/components/ui";
import { RouteTracker } from "@/lib/history";
import { StoreProvider } from "@/store/StoreProvider";
import "./globals.css";

const notoSans = Noto_Sans({ variable: "--font-noto-sans", subsets: ["latin"] });

// The site's stars: Google's Material Symbols Outlined (Apache
// 2.0, fonts/LICENSE-material-symbols.txt), served from this origin and cut
// down to the two icons used, star_rate and star_rate_half, with all four
// axes (StarRating fills them with FILL): 2.5 KB rather than 4 MB. Using
// another icon means fetching the subset again with its name added:
//   https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined:opsz,wght,FILL,GRAD@20..48,100..700,0..1,-50..200&icon_names=star_rate,star_rate_half&display=block
// (the woff2 that stylesheet names). display: block, so the ligatures never
// show as words ("star_rate") while it loads; no fallback font, as no other
// font has these glyphs.
const materialSymbols = localFont({
  src: "./fonts/material-symbols-stars.woff2",
  variable: "--font-material-symbols",
  weight: "100 700",
  display: "block",
  adjustFontFallback: false,
});

// The content-security nonce is read from the request, so this document cannot
// be a prerendered shell: its scripts have to carry the nonce of that request.
export const instant = false;

export const metadata: Metadata = {
  title: { default: "Product Reviews • Portfolio Demo", template: "%s · Product Reviews • Portfolio Demo" },
  description: "Portfolio demo: JSON-LD product reviews with optimistic concurrency, audit logging, and role-based auth.",
};

export default async function RootLayout({ children }: LayoutProps<"/">) {
  // Rendered for each request, as Next.js stamps its scripts with that
  // request's nonce (src/proxy.ts).
  await connection();
  return (
    <html lang="en" className={`${notoSans.variable} ${materialSymbols.variable} antialiased`}>
      <body className="flex min-h-screen flex-col font-sans">
        {/*
          The store is made per browser tab and per request, never prerendered
          (RTK Query names its instance with Math.random()).
        */}
        <Suspense fallback={<Loading />}>
          <StoreProvider>
            <RouteTracker />
            <NavBar />
            <main className="w-full flex-1">{children}</main>
          </StoreProvider>
        </Suspense>
        <Footer />
      </body>
    </html>
  );
}

function Loading() {
  return (
    <>
      <nav className="mx-auto flex w-full max-w-6xl items-center px-4 py-4 xl:px-0">
        <Link href="/" className="text-3xl font-bold leading-loose">
          Product Reviews
        </Link>
      </nav>
      <main className="w-full flex-1">
        <Spinner />
      </main>
    </>
  );
}
