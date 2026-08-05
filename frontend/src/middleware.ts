import { NextRequest, NextResponse } from "next/server";

const PUBLIC_PATHS = ["/login"];
const NO_INDEX_VALUE = "noindex, nofollow, noarchive";

function withNoIndex(response: NextResponse) {
  response.headers.set("X-Robots-Tag", NO_INDEX_VALUE);
  return response;
}

/**
 * Route guard: redirects unauthenticated users to /login. The session check
 * only inspects the presence of the httpOnly access token cookie.
 */
export function middleware(req: NextRequest) {
  const { pathname } = req.nextUrl;
  const isPublic = PUBLIC_PATHS.some((p) => pathname.startsWith(p));
  const isApi = pathname.startsWith("/api");

  if (isApi || isPublic) {
    return withNoIndex(NextResponse.next());
  }

  const hasSession = Boolean(
    req.cookies.get("access_token")?.value || req.cookies.get("refresh_token")?.value,
  );
  if (!hasSession) {
    const loginUrl = req.nextUrl.clone();
    loginUrl.pathname = "/login";
    return withNoIndex(NextResponse.redirect(loginUrl));
  }

  return withNoIndex(NextResponse.next());
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
