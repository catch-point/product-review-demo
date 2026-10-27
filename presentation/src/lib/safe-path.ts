/** Stands in for this application's origin while a path is resolved. */
const BASE = "http://application.invalid";

/**
 * A path of this application to go to after signing in (`/products/1?x=y`),
 * or null for anything else. It is resolved as a redirect or the router will
 * resolve it, since the URL parser drops tabs and newlines and reads `\` as
 * `/`: `/\t/evil.example` and `/\evil.example` are `//evil.example`, another
 * site. Used by the server (src/server/http.ts) and the browser
 * (KeycloakHandoff) alike.
 */
export function safePath(next: string | null | undefined): string | null {
  if (!next?.startsWith("/")) {
    return null;
  }
  let url: URL;
  try {
    url = new URL(next, BASE);
  } catch {
    return null;
  }
  return url.origin === BASE ? `${url.pathname}${url.search}${url.hash}` : null;
}
