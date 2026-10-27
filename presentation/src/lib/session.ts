/** What the browser knows about the signed-in user (GET /api/auth/session). */

export const ROLES = ["reviewer", "owner"] as const;

export type Role = (typeof ROLES)[number];

export interface Session {
  email: string;
  name: string;
  role: Role;
}

export function isRole(value: unknown): value is Role {
  return typeof value === "string" && (ROLES as readonly string[]).includes(value);
}

export const ROLE_LABELS: Record<Role, string> = {
  reviewer: "Reviewer",
  owner: "Product owner",
};
