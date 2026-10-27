import { Server } from "node:http";

import * as preload from "../../client-address.cjs";

/**
 * client-address.cjs, installed as `node --require` installs it in the server
 * (under Vitest it does not install itself). It stays for the worker, and is
 * installed only once.
 */
export function installPreload(): void {
  preload.install(preload.trustedProxies(""));
}

/** Runs as a server started without the preload would: the same emit, without its mark. */
export function withoutPreload<T>(run: () => T): T {
  const emit = Server.prototype.emit;
  Server.prototype.emit = function (this: Server, ...args: Parameters<typeof emit>) {
    return emit.apply(this, args);
  } as typeof emit;
  try {
    return run();
  } finally {
    Server.prototype.emit = emit;
  }
}
