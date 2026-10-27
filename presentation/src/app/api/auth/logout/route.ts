import { NextResponse, type NextRequest } from "next/server";

import { clientAddressOf } from "@/server/client-address";
import { clearCookie, crossSite, problem, SESSION_COOKIE } from "@/server/http";
import { endSession } from "@/server/oidc";
import { tokensOf } from "@/server/session";

/** Signs out here and at Keycloak, so its sign-in page asks for the password again. */
export async function POST(request: NextRequest): Promise<Response> {
  if (crossSite(request)) {
    return problem(403, "Forbidden", "Cross-site request refused");
  }
  const tokens = await tokensOf(request);
  if (tokens) {
    await endSession(tokens, clientAddressOf(request.headers));
  }
  const response = new NextResponse(null, { status: 204 });
  clearCookie(response, SESSION_COOKIE);
  return response;
}
