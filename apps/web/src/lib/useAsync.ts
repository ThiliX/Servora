import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "./api";

export type AsyncState<T> =
  | { status: "loading" }
  | { status: "error"; error: ApiError }
  | { status: "ready"; data: T };

export function toApiError(error: unknown): ApiError {
  return error instanceof ApiError ? error : new ApiError(0, "UNEXPECTED_ERROR", "Something went wrong.");
}

/** Loads data when `key` changes and exposes loading, error and ready states plus a reload function. */
export function useAsync<T>(load: () => Promise<T>, key: string): { state: AsyncState<T>; reload: () => void } {
  const [state, setState] = useState<AsyncState<T>>({ status: "loading" });
  const [version, setVersion] = useState(0);

  const loadedKey = useRef<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    // A reload of the same key keeps showing the current data until the new data arrives.
    if (loadedKey.current !== key) {
      setState({ status: "loading" });
    }
    loadedKey.current = key;
    load()
      .then((data) => {
        if (!cancelled) {
          setState({ status: "ready", data });
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setState({ status: "error", error: toApiError(error) });
        }
      });
    return () => {
      cancelled = true;
    };
    // `load` is intentionally not a dependency: callers pass an inline function and control
    // reloading through `key`.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, version]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);
  return { state, reload };
}
