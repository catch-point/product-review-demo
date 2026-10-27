/**
 * Runs once, before the server takes a request (Next.js instrumentation). In
 * production the server will not start when it could not tell its own
 * address or its clients' (server/deployment.ts); in development each problem
 * is a warning.
 */
export async function register() {
  if (process.env.NEXT_RUNTIME === "nodejs") {
    const { enforceDeployment } = await import("./server/deployment");
    enforceDeployment();
  }
}
