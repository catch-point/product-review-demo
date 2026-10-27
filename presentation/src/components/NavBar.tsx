"use client";

import Link from "next/link";

import { useLogoutMutation, useSessionQuery } from "@/store/auth";

const LINK = "px-3 text-sm font-semibold text-blue-500 hover:text-blue-400";

/** The site's nav, with the links the signed-in user's role allows. */
export function NavBar() {
  const { data: session, isLoading } = useSessionQuery();
  // The page's RequireSession then leaves for the sign-in page.
  const [logout] = useLogoutMutation();

  return (
    <nav className="mx-auto flex w-full max-w-6xl flex-wrap items-center justify-between gap-2 px-4 py-4 xl:px-0">
      <div className="leading-loose">
        <Link href="/" className="text-3xl font-bold">
          Product Reviews
        </Link>
        <span className="ml-2 align-middle rounded-full bg-blue-50 px-2 py-0.5 text-xs font-semibold text-blue-600">
          Portfolio Demo
        </span>
      </div>

      <div className="flex flex-wrap items-center leading-loose">
        {isLoading ? null : session ? (
          <>
            {session.role === "owner" ? (
              <>
                <Link href="/my-products" className={LINK}>
                  My Products
                </Link>
                <Link href="/products/new" className={LINK}>
                  Add Product
                </Link>
              </>
            ) : (
              <Link href="/" className={LINK}>
                Products
              </Link>
            )}
            <span className="px-3 text-sm text-gray-500" title={session.email}>
              {session.name}
            </span>
            <button type="button" onClick={() => void logout()} className={`${LINK} cursor-pointer`}>
              Sign out
            </button>
          </>
        ) : (
          <>
            <Link href="/login" className={LINK}>
              Sign in
            </Link>
            <Link href="/register" className={LINK}>
              Register
            </Link>
          </>
        )}
      </div>
    </nav>
  );
}
