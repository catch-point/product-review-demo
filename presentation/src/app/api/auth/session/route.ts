import { connection, NextResponse, type NextRequest } from "next/server";

import { currentSession, updateSessionCookie } from "@/server/session";

/** The signed-in user, or `null`. Never cached: it depends on the cookie. */
export async function GET(request: NextRequest): Promise<Response> {
  // Per request, never prerendered: the answer depends on the cookie.
  await connection();
  const { user, cookie } = await currentSession(request);
  const response = NextResponse.json(user, { headers: { "Cache-Control": "private, no-store" } });
  updateSessionCookie(response, request, cookie);
  return response;
}
