import { NextRequest, NextResponse } from "next/server";
import { API_BASE } from "@/lib/serverApi";
import { rejectCrossOriginMutation, setSessionCookies } from "@/lib/serverSecurity";

/**
 * GET /api/auth/session — returns whether a session cookie exists (without
 * exposing the JWT).
 * POST /api/auth/session — exchanges admin credentials for JWT tokens and
 * stores them in httpOnly cookies. The browser never sees the tokens.
 */
export async function GET(req: NextRequest) {
  const hasSession = Boolean(
    req.cookies.get("access_token")?.value || req.cookies.get("refresh_token")?.value,
  );
  return NextResponse.json({ authenticated: hasSession });
}

export async function POST(req: NextRequest) {
  const rejected = rejectCrossOriginMutation(req);
  if (rejected) return rejected;
  const body = await req.json();
  const { username, password } = body as { username?: string; password?: string };

  if (!username || !password) {
    return NextResponse.json({ message: "username and password are required" }, { status: 400 });
  }

  try {
    const res = await fetch(`${API_BASE}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
      cache: "no-store",
    });

    if (!res.ok) {
      return NextResponse.json(
        { message: res.status === 401 ? "Invalid credentials" : "Login failed" },
        { status: res.status }
      );
    }

    const { accessToken, refreshToken } = (await res.json()) as {
      accessToken: string;
      refreshToken: string;
    };

    const response = NextResponse.json({ ok: true });
    setSessionCookies(response, { accessToken, refreshToken });
    return response;
  } catch {
    return NextResponse.json({ message: "Backend unreachable" }, { status: 502 });
  }
}
