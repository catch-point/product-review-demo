import type { NextRequest } from "next/server";

import { access } from "@/server/access";
import { crossSite, problem } from "@/server/http";
import { currentSession, updateSessionCookie } from "@/server/session";
import { bodyLimit, bodyTooLarge, callStorage, declaresMoreThan, readBody, relay } from "@/server/storage";

/**
 * The storage service, for the browser: adds the session's access token
 * (renewing it first when it is about to expire), refuses what the user's
 * role does not allow (src/server/access.ts), and bodies larger than storage
 * accepts (413): at once by their Content-Length, or as soon as reading one
 * passes the limit.
 */
async function proxy(request: NextRequest, ctx: RouteContext<"/api/storage/[...path]">): Promise<Response> {
  const { path: segments } = await ctx.params;
  if (segments.some((s) => s === "." || s === ".." || s.includes("/"))) {
    return problem(404, "Not Found", "No such resource");
  }
  const path = segments.map(encodeURIComponent).join("/");
  const method = request.method.toUpperCase();
  const safe = method === "GET" || method === "HEAD";
  if (!safe && crossSite(request)) {
    return problem(403, "Forbidden", "Cross-site request refused");
  }
  const limit = bodyLimit(path);
  if (!safe && declaresMoreThan(request, limit)) {
    return bodyTooLarge(path);
  }

  const session = await currentSession(request);
  const decision = access(method, path, session.user?.role ?? null);
  if (!decision.allowed) {
    const title = { 401: "Unauthorized", 403: "Forbidden", 404: "Not Found" }[decision.status];
    const refused = problem(decision.status, title, decision.detail);
    updateSessionCookie(refused, request, session.cookie);
    return refused;
  }

  const body = safe ? null : await readBody(request, limit);
  if (!safe && !body) {
    const refused = bodyTooLarge(path);
    updateSessionCookie(refused, request, session.cookie);
    return refused;
  }
  const upstream = await callStorage(request, {
    method,
    path,
    search: request.nextUrl.search,
    headers: request.headers,
    body,
    token: session.accessToken,
  });
  const response = relay(upstream);
  updateSessionCookie(response, request, session.cookie);
  return response;
}

export const GET = proxy;
export const HEAD = proxy;
export const POST = proxy;
export const PUT = proxy;
export const DELETE = proxy;
