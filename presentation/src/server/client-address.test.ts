import { createServer, request as httpRequest, type Server } from "node:http";
import type { AddressInfo } from "node:net";

import { afterAll, beforeAll, describe, expect, it } from "vitest";

// The preload, as `node --require` loads it; under Vitest it does not install itself.
import * as preload from "../../client-address.cjs";
import { installPreload, withoutPreload } from "@/test/preload";

import { clientAddressOf, forwardingHeadersChecked } from "./client-address";

describe("client-address.cjs", () => {
  const trusted = preload.trustedProxies("10.0.0.5, 172.31.250.0/28, ::1");

  it("is the peer, unless the peer is a trusted proxy", () => {
    expect(preload.clientAddress("198.51.100.7", "203.0.113.1", trusted)).toBe("198.51.100.7");
    expect(preload.clientAddress("10.0.0.5", "203.0.113.1", trusted)).toBe("203.0.113.1");
    expect(preload.clientAddress("::ffff:10.0.0.5", "203.0.113.1", trusted)).toBe("203.0.113.1");
    expect(preload.clientAddress("::1", undefined, trusted)).toBe("::1");
  });

  it("is the last address before the trusted proxies, whatever the client put first", () => {
    expect(preload.clientAddress("10.0.0.5", "1.1.1.1, 203.0.113.1, 172.31.250.10", trusted)).toBe("203.0.113.1");
    expect(preload.clientAddress("10.0.0.5", ["1.1.1.1", "203.0.113.1"], trusted)).toBe("203.0.113.1");
  });

  it("trusts no one by default, and refuses what is not an address", () => {
    expect(preload.clientAddress("127.0.0.1", "203.0.113.1", preload.trustedProxies(undefined))).toBe("127.0.0.1");
    expect(() => preload.trustedProxies("proxy.example")).toThrow(/not an IP address/);
  });

  it("keeps the other forwarding headers from a trusted proxy alone", () => {
    const forwarded = () => ({
      "x-forwarded-for": "203.0.113.1",
      "x-forwarded-host": "evil.example",
      "x-forwarded-proto": "https",
      "x-forwarded-port": "443",
      "x-forwarded-prefix": "/evil",
      forwarded: "host=evil.example",
      host: "localhost:3000",
    });
    const fromClient: Record<string, string | undefined> = forwarded();
    preload.rewrite("198.51.100.7", fromClient, trusted);
    expect(fromClient).toEqual({ "x-forwarded-for": "198.51.100.7", host: "localhost:3000" });
    const fromProxy: Record<string, string | undefined> = forwarded();
    preload.rewrite("10.0.0.5", fromProxy, trusted);
    expect(fromProxy).toEqual(forwarded());
  });

  describe("installed", () => {
    let server: Server;
    let port: number;
    beforeAll(async () => {
      preload.install(preload.trustedProxies(""));
      server = createServer((req, res) => res.end(req.headers["x-forwarded-for"] ?? ""));
      await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
      port = (server.address() as AddressInfo).port;
    });
    afterAll(() => new Promise<void>((resolve) => server.close(() => resolve())));

    it("replaces what the client sent with the address it connected from", async () => {
      const seen = await new Promise<string>((resolve, reject) => {
        const req = httpRequest({ port, host: "127.0.0.1", headers: { "X-Forwarded-For": "203.0.113.1" } }, (res) => {
          let body = "";
          res.on("data", (chunk) => (body += chunk));
          res.on("end", () => resolve(body));
        });
        req.on("error", reject);
        req.end();
      });
      expect(seen).toBe("127.0.0.1");
    });
  });
});

describe("clientAddressOf", () => {
  beforeAll(installPreload);

  it("is the address in X-Forwarded-For, if it is one", () => {
    expect(forwardingHeadersChecked()).toBe(true);
    expect(clientAddressOf(new Headers({ "X-Forwarded-For": "203.0.113.1" }))).toBe("203.0.113.1");
    expect(clientAddressOf(new Headers({ "X-Forwarded-For": "2001:db8::1" }))).toBe("2001:db8::1");
    expect(clientAddressOf(new Headers({ "X-Forwarded-For": "proxy.example" }))).toBeUndefined();
    expect(clientAddressOf(new Headers())).toBeUndefined();
  });

  it("is none without the preload, as the header is then the client's own", () => {
    withoutPreload(() => {
      expect(forwardingHeadersChecked()).toBe(false);
      expect(clientAddressOf(new Headers({ "X-Forwarded-For": "203.0.113.1" }))).toBeUndefined();
    });
  });
});
