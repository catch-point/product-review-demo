import { createSlice } from "@reduxjs/toolkit";

import { authApi } from "./auth";

/**
 * Whether the user just signed out in this tab, so the page they were on
 * sends them to sign in afresh, not back to that page (which would be the
 * wrong one for the next person to sign in here).
 */
export const authSlice = createSlice({
  name: "auth",
  initialState: { signedOut: false },
  reducers: {},
  extraReducers: (builder) => {
    // Signing in again reloads the page, which starts a new store.
    builder.addMatcher(authApi.endpoints.logout.matchFulfilled, (state) => {
      state.signedOut = true;
    });
  },
  selectors: {
    selectSignedOut: (state) => state.signedOut,
  },
});

export const { selectSignedOut } = authSlice.selectors;
