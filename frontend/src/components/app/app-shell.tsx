"use client";

import { SubscriptionBanner } from "@/components/billing/subscription-banner";
import { LogOut, Menu, X, ArrowLeft } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { GlobalSearch } from "@/components/app/global-search";
import { NotificationBell } from "@/components/app/notification-bell";
import { VerifyEmailBanner } from "@/components/app/verify-email-banner";
import { Button } from "@/components/ui/button";
import { useMe } from "@/hooks/use-me";
import { hardNavigate, session } from "@/lib/api";
import { CONTROL_CENTER_NAV, visibleNavItems } from "@/lib/navigation";

const LINK = {
  workspace: { on: "bg-primary text-primary-foreground", off: "hover:bg-muted" },
  center: { on: "bg-blue-600 text-white", off: "text-slate-200 hover:bg-slate-800" },
};

export function AppShell({ children }: { children: ReactNode }) {
  const { data: me } = useMe();
  const pathname = usePathname();
  const router = useRouter();
  const [menuOpen, setMenuOpen] = useState(false);

  useEffect(() => {
    if (me && !me.onboarded && me.permissions.includes("TENANT_MANAGE")) {
      router.replace("/onboarding");
    }
  }, [me, router]);

  async function switchOrganization(tenantId: string) {
    await session.post("switch-organization", { tenantId });
    hardNavigate("/dashboard");
  }

  async function signOut() {
    await session.post("logout", {});
    hardNavigate("/login");
  }

  const controlCenter = pathname === "/platform" || pathname.startsWith("/platform/");
  const link = (href: string, active: boolean) =>
    `flex items-center gap-2 rounded-md px-2 py-2 text-sm ${LINK[controlCenter ? "center" : "workspace"][active ? "on" : "off"]}`;

  if (!me) {
    return <p className="p-6 text-sm text-muted-foreground">Loading…</p>;
  }

  return (
    <div className="flex min-h-svh flex-col md:flex-row">
      <header className="flex items-center justify-between border-b px-4 py-3 md:hidden print:hidden">
        <span className="font-heading font-semibold text-primary">
          {controlCenter ? "QualityOS Control Center" : "QualityOS"}
        </span>
        <span className="flex-1" />
        <NotificationBell />
        <Button
          variant="ghost"
          size="icon"
          aria-label={menuOpen ? "Close menu" : "Open menu"}
          aria-expanded={menuOpen}
          onClick={() => setMenuOpen(!menuOpen)}
        >
          {menuOpen ? <X /> : <Menu />}
        </Button>
      </header>

      <aside
        className={`${menuOpen ? "flex" : "hidden"} flex-col gap-4 border-b ${controlCenter ? "bg-slate-900 text-slate-100" : "bg-muted/40"} p-4 md:flex md:w-60 md:shrink-0 md:border-r md:border-b-0 print:hidden`}
      >
        <div className="hidden items-center justify-between md:flex">
          <span className={`font-heading text-lg font-semibold ${controlCenter ? "text-white" : "text-primary"}`}>
            {controlCenter ? "QualityOS Control Center" : "QualityOS"}
          </span>
          <NotificationBell />
        </div>

        {me.organizations.length > 1 ? (
          <select
            aria-label="Organisation"
            className="h-9 w-full rounded-md border bg-background px-2 text-sm"
            value={me.tenantId}
            onChange={(event) => switchOrganization(event.target.value)}
          >
            {me.organizations.map((organization) => (
              <option key={organization.id} value={organization.id}>
                {organization.name}
              </option>
            ))}
          </select>
        ) : (
          <p className="truncate text-sm text-muted-foreground">{me.organizations[0]?.name}</p>
        )}

        {!controlCenter && me.permissions.includes("TRACEABILITY_VIEW") && <GlobalSearch />}

        {controlCenter ? (
          <nav aria-label="Control Center" className="flex flex-1 flex-col gap-4">
            {CONTROL_CENTER_NAV.map(({ heading, items }) => (
              <div key={heading} className="space-y-1">
                <p className="px-2 text-xs font-medium tracking-wide text-slate-400 uppercase">{heading}</p>
                {visibleNavItems(me?.permissions ?? [], items).map(({ href, label, icon: Icon }) => (
                  <Link
                    key={href}
                    href={href}
                    aria-current={pathname.startsWith(href) ? "page" : undefined}
                    onClick={() => setMenuOpen(false)}
                    className={link(href, pathname.startsWith(href))}
                  >
                    <Icon aria-hidden className="size-4" />
                    {label}
                  </Link>
                ))}
              </div>
            ))}
            <Link href="/dashboard" className={link("/dashboard", false)}>
              <ArrowLeft aria-hidden className="size-4" />
              Back to workspace
            </Link>
          </nav>
        ) : (
          <nav aria-label="Main" className="flex flex-1 flex-col gap-1">
            {visibleNavItems(me.permissions).map(({ href, label, icon: Icon }) => {
              const active = pathname === href || pathname.startsWith(`${href}/`);
              return (
                <Link
                  key={href}
                  href={href}
                  aria-current={active ? "page" : undefined}
                  onClick={() => setMenuOpen(false)}
                  className={link(href, active)}
                >
                  <Icon aria-hidden className="size-4" />
                  {label}
                </Link>
              );
            })}
          </nav>
        )}

        <div className="space-y-2 border-t pt-3">
          <p className="truncate text-sm">{me.user.fullName}</p>
          <Button variant="ghost" size="sm" className="w-full justify-start" onClick={signOut}>
            <LogOut aria-hidden />
            Sign out
          </Button>
        </div>
      </aside>

      <main className="min-w-0 flex-1 p-4 md:p-8">
        {!me.user.emailVerified && <VerifyEmailBanner email={me.user.email} />}
        <SubscriptionBanner />
        {children}
      </main>
    </div>
  );
}
