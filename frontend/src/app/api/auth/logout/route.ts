import { NextResponse } from "next/server";

/**
 * POST /api/auth/logout — clears the session cookies.
 */
export async function POST() {
  const response = NextResponse.json({ ok: true });
  response.cookies.set("access_token", "", { httpOnly: true, path: "/", maxAge: 0 });
  response.cookies.set("refresh_token", "", { httpOnly: true, path: "/", maxAge: 0 });
  return response;
}
