import type { Session } from "@/lib/session";
import type { Person } from "@/lib/types";
import { api, storage } from "./api";

/**
 * Signing in and registering happen on Keycloak's pages (see /login and
 * /register), which return through /api/auth/callback with a full page load.
 */
export const authApi = api.injectEndpoints({
  endpoints: (build) => ({
    session: build.query<Session | null, void>({
      query: () => ({ url: "auth/session", headers: { Accept: "application/json" } }),
      providesTags: ["Session"],
    }),
    /** The signed-in person as the storage service knows them (their IRI and products). */
    me: build.query<Person, void>({
      query: () => storage("people/me"),
      providesTags: ["Me"],
    }),
    logout: build.mutation<void, void>({
      query: () => ({ url: "auth/logout", method: "POST" }),
      // "Me" is read again by whoever signs in next, after a full page load.
      invalidatesTags: ["Session"],
    }),
  }),
});

export const { useSessionQuery, useMeQuery, useLogoutMutation } = authApi;
