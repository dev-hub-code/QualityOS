import { NextResponse, type NextRequest } from "next/server";
import { REFRESH_COOKIE } from "@/lib/bff";

const PUBLIC_PAGES = ["/login", "/register", "/status"];

/** A convenience redirect only: the backend enforces all access. */
export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const signedIn = request.cookies.has(REFRESH_COOKIE);

  if (!signedIn && !PUBLIC_PAGES.includes(pathname)) {
    return NextResponse.redirect(new URL("/login", request.url));
  }
  if (signedIn && (pathname === "/login" || pathname === "/register" || pathname === "/")) {
    return NextResponse.redirect(new URL("/dashboard", request.url));
  }
  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!api|_next|favicon.ico).*)"],
};
