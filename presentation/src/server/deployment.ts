import "server-only";

import { forwardingHeadersChecked } from "./client-address";
import { publicUrl } from "./config";

/**
 * What would leave this server open to its clients' say-so, as messages:
 * no PUBLIC_URL, so links and redirects would be built from the request's
 * Host (which a shared cache could then serve to everyone); or no
 * client-address.cjs, so whoever connects could name any client address, and
 * spend someone else's rate limits or sign-in attempts.
 */
export function deploymentProblems(): string[] {
  const problems: string[] = [];
  try {
    if (!publicUrl()) {
      problems.push(
        "PUBLIC_URL is not set: set it to the address browsers reach this application at (http://localhost:3000)",
      );
    }
  } catch (error) {
    problems.push((error as Error).message);
  }
  if (!forwardingHeadersChecked()) {
    problems.push(
      "client-address.cjs is not loaded: start the server with node --require ./client-address.cjs" +
        " (npm run dev, npm start and the Docker image do)",
    );
  }
  return problems;
}

/**
 * Before the server takes a request (instrumentation.ts): in production any
 * problem stops the process, as Next.js would otherwise stay up and fail
 * every request; in development each is a warning.
 */
export function enforceDeployment(): void {
  const problems = deploymentProblems();
  if (problems.length === 0) {
    return;
  }
  if (process.env.NODE_ENV === "production") {
    console.error(`Refusing to start:\n${problems.map((problem) => `- ${problem}`).join("\n")}`);
    process.exit(1);
  }
  for (const problem of problems) {
    console.warn(`Warning: ${problem}`);
  }
}
