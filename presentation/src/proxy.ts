import { NextResponse, type NextRequest } from "next/server";

/**
 * The document policy. A new nonce each request, so a script written into the
 * page cannot run unless it carries that nonce. 'strict-dynamic' lets Next's
 * own scripts load their bundles. Development also allows 'unsafe-eval'
 * (React's debugger) and inline styles (its error overlay).
 *
 * There is no upgrade-insecure-requests: the stack is served over HTTP.
 * Images are this origin, a local preview (blob:), or an in-page data URL.
 * Fonts, the icons' included, are this origin's (next/font, layout.tsx):
 * nothing is loaded from another host.
 */
export function contentSecurityPolicy(nonce: string, dev: boolean): string {
  const script = dev
    ? `script-src 'self' 'nonce-${nonce}' 'strict-dynamic' 'unsafe-eval'`
    : `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'`;
  const style = dev
    ? "style-src 'self' 'unsafe-inline'"
    : `style-src 'self' 'nonce-${nonce}'`;
  return [
    "default-src 'self'",
    script,
    style,
    "img-src 'self' blob: data:",
    "font-src 'self'",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join("; ");
}

function nonce(): string {
  return Buffer.from(crypto.randomUUID()).toString("base64");
}

export function proxy(_request: NextRequest) {
  const value = nonce();
  const policy = contentSecurityPolicy(value, process.env.NODE_ENV === "development");
  const requestHeaders = new Headers(_request.headers);
  // Next.js reads the nonce from this request header and stamps it on its scripts.
  requestHeaders.set("x-nonce", value);
  requestHeaders.set("Content-Security-Policy", policy);
  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set("Content-Security-Policy", policy);
  return response;
}

export const config = {
  matcher: [
    {
      // API responses keep their own headers (an image's sandbox policy).
      // Static files are not documents.
      source: "/((?!api|_next/static|_next/image|favicon.ico).*)",
      missing: [
        { type: "header", key: "next-router-prefetch" },
        { type: "header", key: "purpose", value: "prefetch" },
      ],
    },
  ],
};
