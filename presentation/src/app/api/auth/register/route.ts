import type { NextRequest } from "next/server";

import { redirectToKeycloak } from "@/server/login";

/**
 * `/api/auth/register?next=/`: to Keycloak's registration page, where people
 * choose whether they review products or own them. Registering signs them in.
 */
export function GET(request: NextRequest): Promise<Response> {
  return redirectToKeycloak(request, true);
}
