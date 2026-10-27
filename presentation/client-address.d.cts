import type { BlockList } from "node:net";

/** TRUSTED_PROXIES ("10.0.0.1, 172.16.0.0/12") as a BlockList. */
export function trustedProxies(spec: string | undefined): BlockList;

/** The client: the peer, or (from a trusted proxy) the last untrusted address it forwarded. */
export function clientAddress(peer: string | undefined, forwardedFor: string | string[] | undefined, trusted: BlockList): string;

/** The request's headers as a trusted proxy left them: the peer's own forwarding headers dropped. */
export function rewrite(peer: string | undefined, headers: Record<string, string | string[] | undefined>, trusted: BlockList): void;

/** Rewrites the forwarding headers of every request any HTTP server in this process receives. */
export function install(trusted: BlockList): void;
