import { NextRequest, NextResponse } from "next/server";
import { API_BASE } from "@/lib/serverApi";
import { clearSessionCookies, rejectCrossOriginMutation } from "@/lib/serverSecurity";

/**
 * POST /api/auth/logout — clears the session cookies.
 */
export async function POST(req: NextRequest) {
  const rejected = rejectCrossOriginMutation(req);
  if (rejected) return rejected;
  const refreshToken = req.cookies.get("refresh_token")?.value;
  if (refreshToken) {
    await fetch(`${API_BASE}/api/auth/logout`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
      cache: "no-store",
    }).catch(() => undefined);
  }
  const response = NextResponse.json({ ok: true });
  clearSessionCookies(response);
  return response;
}
