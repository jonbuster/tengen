import { NextRequest, NextResponse } from "next/server";
import { API_BASE } from "@/lib/api";

/**
 * Proxy for authenticated admin API calls. Reads the httpOnly session cookie,
 * attaches the JWT as a Bearer token and forwards to the Spring Boot backend.
 * Refreshes the access token on 401 via the refresh token, then retries once.
 */
async function handle(
  req: NextRequest,
  ctx: { params: Promise<{ path: string[] }> }
): Promise<NextResponse> {
  const { path: segments } = await ctx.params;
  const path = segments.join("/");
  const accessToken = req.cookies.get("access_token")?.value;
  const refreshToken = req.cookies.get("refresh_token")?.value;
  const body = req.method !== "GET" && req.method !== "HEAD" ? await req.text() : undefined;

  let res = await forward(req, path, accessToken, body);
  if (res.status === 401 && refreshToken) {
    const refreshed = await refreshAccessToken(refreshToken);
    if (refreshed) {
      res = await forward(req, path, refreshed.accessToken, body);
      const cookieRes = new NextResponse(res.body, { status: res.status, headers: res.headers });
      cookieRes.cookies.set("access_token", refreshed.accessToken, {
        httpOnly: true,
        sameSite: "lax",
        secure: process.env.NODE_ENV === "production",
        path: "/",
        maxAge: 15 * 60,
      });
      return cookieRes;
    }
  }
  return res;
}

async function forward(
  req: NextRequest,
  path: string,
  accessToken: string | undefined,
  body: string | undefined
): Promise<NextResponse> {
  const headers: Record<string, string> = {
    "Content-Type": req.headers.get("content-type") ?? "application/json",
  };
  if (accessToken) {
    headers["Authorization"] = `Bearer ${accessToken}`;
  }

  const url = `${API_BASE}/api/${path}`;
  const upstream = await fetch(url, {
    method: req.method,
    headers,
    body,
    cache: "no-store",
  });

  const text = await upstream.text();
  return new NextResponse(text, {
    status: upstream.status,
    headers: { "Content-Type": "application/json" },
  });
}

async function refreshAccessToken(refreshToken: string) {
  try {
    const res = await fetch(`${API_BASE}/api/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
      cache: "no-store",
    });
    if (!res.ok) return null;
    const data = (await res.json()) as { accessToken: string; refreshToken: string };
    return data;
  } catch {
    return null;
  }
}

export async function GET(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  return handle(req, ctx);
}

export async function POST(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  return handle(req, ctx);
}

export async function PUT(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  return handle(req, ctx);
}

export async function PATCH(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  return handle(req, ctx);
}

export async function DELETE(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  return handle(req, ctx);
}
