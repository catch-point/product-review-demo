import type { Role } from "@/lib/session";

/**
 * Which storage requests the browser may make through /api/storage, and as
 * whom. The storage service decides what each person may do, from their role
 * and who owns or wrote each row; this refuses early what a role can never
 * do: only owners manage products and reply to their reviews, and only
 * reviewers review them.
 */
export type Access =
  | { allowed: true }
  | { allowed: false; status: 401 | 403 | 404; detail: string };

const READS =
  /^(context\.jsonld|categories|people(\/(me|\d+))?|products(\/\d+(\/reviews)?)?|reviews\/\d+|comments\/\d+|images\/[0-9a-f]{64}\.[a-z]+)$/;

const BOTH: Role[] = ["reviewer", "owner"];

const WRITES: { method: string; path: RegExp; roles: Role[] }[] = [
  { method: "POST", path: /^products$/, roles: ["owner"] },
  { method: "PUT", path: /^products\/\d+$/, roles: ["owner"] },
  { method: "DELETE", path: /^products\/\d+$/, roles: ["owner"] },
  { method: "POST", path: /^products\/\d+\/reviews$/, roles: ["reviewer"] },
  { method: "PUT", path: /^reviews\/\d+$/, roles: ["reviewer"] },
  { method: "DELETE", path: /^reviews\/\d+$/, roles: ["reviewer"] },
  // The product's owner replies to its reviews, once (storage checks that they own it).
  { method: "POST", path: /^reviews\/\d+\/comments$/, roles: ["owner"] },
  { method: "PUT", path: /^comments\/\d+$/, roles: ["owner"] },
  { method: "DELETE", path: /^comments\/\d+$/, roles: ["owner"] },
  { method: "POST", path: /^images$/, roles: BOTH },
  { method: "PUT", path: /^people\/\d+$/, roles: BOTH },
  // Registering (POST /people) happens in /api/auth/register, and deleting a
  // person would leave their account behind, so neither is proxied.
];

export function access(method: string, path: string, role: Role | null): Access {
  if (method === "GET" || method === "HEAD") {
    return READS.test(path) ? { allowed: true } : notFound();
  }
  const rule = WRITES.find((w) => w.method === method && w.path.test(path));
  if (!rule) {
    return notFound();
  }
  if (!role) {
    return { allowed: false, status: 401, detail: "Sign in first" };
  }
  if (!rule.roles.includes(role)) {
    return {
      allowed: false,
      status: 403,
      detail: role === "owner" ? "Product owners cannot do this" : "Only product owners can do this",
    };
  }
  return { allowed: true };
}

function notFound(): Access {
  return { allowed: false, status: 404, detail: "No such resource" };
}
