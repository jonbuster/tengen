import axios from "axios";

/**
 * Axios instance that talks to the Next.js API routes. All backend calls go
 * through the server side so the JWT never reaches client JS.
 */
export const api = axios.create({
  baseURL: "/api/proxy",
  headers: {
    "Content-Type": "application/json",
  },
});

/** Extract a readable message from an axios error. */
export function errorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as { message?: string } | undefined;
    if (data?.message) return data.message;
    return err.message;
  }
  return err instanceof Error ? err.message : "Unknown error";
}
