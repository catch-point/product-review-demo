import type { NextRequest } from "next/server";

import { redirectToKeycloak } from "@/server/login";

/** `/api/auth/login?next=/products/1`: to Keycloak's sign-in page, and back to `next`. */
export function GET(request: NextRequest): Promise<Response> {
  return redirectToKeycloak(request, false);
}
