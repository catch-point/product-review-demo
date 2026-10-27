import "server-only";

import { Server } from "node:http";
import { isIP } from "node:net";

/**
 * Whether client-address.cjs rewrites this process's requests: the server
 * was started with `node --require ./client-address.cjs`, as the npm scripts
 * and the Docker image are. Without it, X-Forwarded-For and the other
 * forwarding headers are whatever the client sent.
 */
export function forwardingHeadersChecked(): boolean {
  return (Server.prototype.emit as { clientAddress?: boolean }).clientAddress === true;
}

/**
 * The address of the client a request is for, as client-address.cjs left it
 * in X-Forwarded-For: resolved from the connection (and TRUSTED_PROXIES), so
 * the client cannot choose it. Passed on to the storage service and Keycloak,
 * which trust it from this server alone. Without the preload there is none:
 * the header would be the client's own choice.
 */
export function clientAddressOf(headers: Headers): string | undefined {
  if (!forwardingHeadersChecked()) {
    return undefined;
  }
  const address = headers.get("x-forwarded-for")?.split(",")[0].trim();
  return address && isIP(address) ? address : undefined;
}
