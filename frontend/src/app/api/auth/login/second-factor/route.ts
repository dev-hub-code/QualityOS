import { NextResponse, type NextRequest } from "next/server";
import { issueSessionVia } from "@/lib/auth-route";
import { CHALLENGE_COOKIE } from "@/lib/bff";

/** The second half of an operator's sign-in. The challenge from the first half is in a cookie, never the page. */
export function POST(request: NextRequest) {
  const challengeToken = request.cookies.get(CHALLENGE_COOKIE)?.value;
  if (!challengeToken) {
    return NextResponse.json(
      {
        success: false,
        error: { code: "INVALID_CREDENTIALS", message: "The sign-in has expired. Please start again." },
      },
      { status: 401 },
    );
  }
  return issueSessionVia(request, "auth/login/second-factor", (body) => ({ challengeToken, code: body.code }));
}
