import { createHash } from "node:crypto";

import { EncryptJWT, jwtDecrypt } from "jose";

/**
 * Values kept in cookies, encrypted and authenticated (JWE, A256GCM), so the
 * browser holds them but can neither read nor change them. This is how the
 * application keeps sessions without storing anything itself.
 */

function key(secret: string): Uint8Array {
  return new Uint8Array(createHash("sha256").update(secret, "utf8").digest());
}

export async function seal(value: Record<string, unknown>, secret: string, maxAgeSeconds: number): Promise<string> {
  return new EncryptJWT(value)
    .setProtectedHeader({ alg: "dir", enc: "A256GCM" })
    .setIssuedAt()
    .setExpirationTime(`${maxAgeSeconds}s`)
    .encrypt(key(secret));
}

/** @returns the value, or null when it is missing, expired, forged or sealed with another secret */
export async function unseal<T>(sealed: string | undefined, secret: string): Promise<T | null> {
  if (!sealed) {
    return null;
  }
  try {
    const { payload } = await jwtDecrypt(sealed, key(secret), { keyManagementAlgorithms: ["dir"], contentEncryptionAlgorithms: ["A256GCM"] });
    return payload as T;
  } catch {
    return null;
  }
}
