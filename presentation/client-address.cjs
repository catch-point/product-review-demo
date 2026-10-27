"use strict";

// Says who each request's client is, in X-Forwarded-For, before Next.js sees
// it: the connection's peer, unless the peer is one of TRUSTED_PROXIES (IP
// addresses or CIDR ranges, comma-separated, such as a reverse proxy's), in
// which case the last address in the header that is not a trusted proxy too.
// Next.js keeps any X-Forwarded-For the client sent, and anyone can send one,
// so it is replaced by that single address. The proxy route passes it on to
// the storage service and Keycloak (src/server/client-address.ts). The other
// forwarding headers (X-Forwarded-Host, -Proto, -Port, -Prefix and Forwarded)
// are dropped unless the peer is a trusted proxy, so no client can choose the
// origin this application names itself by (src/server/http.ts publicOrigin).
//
// Preloaded with `node --require ./client-address.cjs` (the dev and start
// scripts, and the Docker image), so it is in place before the server takes
// its first request.

const http = require("node:http");
const { BlockList, isIP } = require("node:net");

/** "10.0.0.1, 172.16.0.0/12" as a BlockList. */
function trustedProxies(spec) {
  const list = new BlockList();
  for (const entry of (spec ?? "").split(",").map((s) => s.trim()).filter(Boolean)) {
    const [address, prefix] = entry.split("/");
    const family = isIP(address) === 6 ? "ipv6" : "ipv4";
    if (!isIP(address)) {
      throw new Error(`TRUSTED_PROXIES: ${entry} is not an IP address or CIDR range`);
    }
    if (prefix === undefined) {
      list.addAddress(address, family);
    } else {
      list.addSubnet(address, Number(prefix), family);
    }
  }
  return list;
}

/** An IPv4 address as Node reports it on a dual-stack socket (::ffff:10.0.0.1) is just that address. */
function normalize(address) {
  const trimmed = (address ?? "").trim();
  return /^::ffff:\d+\.\d+\.\d+\.\d+$/i.test(trimmed) ? trimmed.slice(7) : trimmed;
}

function isTrusted(address, trusted) {
  const family = isIP(address);
  return family !== 0 && trusted.check(address, family === 6 ? "ipv6" : "ipv4");
}

/** The client: the peer, or (from a trusted proxy) the last untrusted address it forwarded. */
function clientAddress(peer, forwardedFor, trusted) {
  const from = normalize(peer);
  if (!isTrusted(from, trusted)) {
    return from;
  }
  const chain = [forwardedFor ?? []]
    .flat()
    .flatMap((header) => header.split(","))
    .map(normalize)
    .filter(Boolean);
  for (let i = chain.length - 1; i >= 0; i--) {
    if (!isTrusted(chain[i], trusted)) {
      return chain[i];
    }
  }
  return chain[0] ?? from;
}

/** What only a trusted proxy may say about a request, besides X-Forwarded-For. */
const FORWARDING = ["x-forwarded-host", "x-forwarded-proto", "x-forwarded-port", "x-forwarded-prefix", "forwarded"];

/** The request's headers as a trusted proxy left them: the peer's own forwarding headers dropped. */
function rewrite(peer, headers, trusted) {
  const address = clientAddress(peer, headers["x-forwarded-for"], trusted);
  if (address) {
    headers["x-forwarded-for"] = address;
  } else {
    delete headers["x-forwarded-for"];
  }
  if (!isTrusted(normalize(peer), trusted)) {
    for (const name of FORWARDING) {
      delete headers[name];
    }
  }
}

/** Rewrites the forwarding headers of every request any HTTP server in this process receives. */
function install(trusted) {
  const emit = http.Server.prototype.emit;
  if (emit.clientAddress) {
    return;
  }
  const patched = function (event, request, ...rest) {
    if (event === "request") {
      rewrite(request.socket.remoteAddress, request.headers, trusted);
    }
    return emit.call(this, event, request, ...rest);
  };
  patched.clientAddress = true;
  http.Server.prototype.emit = patched;
}

module.exports = { trustedProxies, clientAddress, rewrite, install };

if (!process.env.VITEST) {
  install(trustedProxies(process.env.TRUSTED_PROXIES));
}
