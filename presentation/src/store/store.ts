import { configureStore } from "@reduxjs/toolkit";
import { setupListeners } from "@reduxjs/toolkit/query";
import { useDispatch, useSelector } from "react-redux";

import { api } from "./api";
import { authSlice } from "./authSlice";

/** One store per browser tab (see StoreProvider), never shared between requests on the server. */
export function makeStore() {
  const store = configureStore({
    reducer: { [api.reducerPath]: api.reducer, [authSlice.reducerPath]: authSlice.reducer },
    middleware: (getDefault) => getDefault().concat(api.middleware),
  });
  // Refetch on focus and reconnect, where an endpoint asks for it.
  setupListeners(store.dispatch);
  return store;
}

export type AppStore = ReturnType<typeof makeStore>;
export type RootState = ReturnType<AppStore["getState"]>;
export type AppDispatch = AppStore["dispatch"];

export const useAppDispatch = useDispatch.withTypes<AppDispatch>();
export const useAppSelector = useSelector.withTypes<RootState>();
