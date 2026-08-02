import { NextRequest, NextResponse } from "next/server";

export function rejectCrossOriginMutation(req: NextRequest): NextResponse | null {
  const origin = req.headers.get("origin");
  if (!origin) {
    return NextResponse.json({ message: "Origin header is required" }, { status: 403 });
  }
  const forwardedHost = req.headers.get("x-forwarded-host") ?? req.headers.get("host");
  const forwardedProto = req.headers.get("x-forwarded-proto") ?? req.nextUrl.protocol.slice(0, -1);
  if (!forwardedHost || origin !== `${forwardedProto}://${forwardedHost}`) {
    return NextResponse.json({ message: "Cross-origin request rejected" }, { status: 403 });
  }
  return null;
}

export function setSessionCookies(
  response: NextResponse,
  tokens: { accessToken: string; refreshToken: string },
) {
  const common = {
    httpOnly: true,
    sameSite: "lax" as const,
    secure: process.env.NODE_ENV === "production",
    path: "/",
  };
  response.cookies.set("access_token", tokens.accessToken, { ...common, maxAge: 15 * 60 });
  response.cookies.set("refresh_token", tokens.refreshToken, {
    ...common,
    maxAge: 7 * 24 * 60 * 60,
  });
}

export function clearSessionCookies(response: NextResponse) {
  response.cookies.set("access_token", "", { httpOnly: true, path: "/", maxAge: 0 });
  response.cookies.set("refresh_token", "", { httpOnly: true, path: "/", maxAge: 0 });
}
