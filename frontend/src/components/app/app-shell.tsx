"use client";

import { PlatformNotices } from "@/components/app/platform-notices";
import { SubscriptionBanner } from "@/components/billing/subscription-banner";
import { ArrowLeft, LogOut, Menu, PanelLeftClose, PanelLeftOpen, Search, ShieldCheck, X } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { Fragment, useEffect, useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { GlobalSearch } from "@/components/app/global-search";
import { NotificationBell } from "@/components/app/notification-bell";
import { VerifyEmailBanner } from "@/components/app/verify-email-banner";
import { Button } from "@/components/ui/button";
import { useMe } from "@/hooks/use-me";
import { api, hardNavigate, session } from "@/lib/api";
import type { ManufacturingSettings } from "@/lib/types";
import {
  activeNavHref,
  CONTROL_CENTER_NAV,
  forManufacturingModel,
  leavesWorkspace,
  visibleNavItems,
  workspaceNavItems,
} from "@/lib/navigation";

const LINK = {
  workspace: {
    on: "bg-gradient-to-b from-[#2f4590] to-[#1c2a62] text-white shadow-[0_8px_18px_-10px_rgba(28,42,98,0.9)]",
    off: "text-[#2b3a66] hover:bg-white/70",
  },
  center: { on: "bg-white text-[#1b2a55] shadow", off: "text-slate-200 hover:bg-white/10" },
};

const COLLAPSED_KEY = "qualityos.menu-collapsed";

/** Remembered per browser. Storage can be missing or blocked, so every access is guarded. */
function storedCollapsed() {
  try {
    return typeof window !== "undefined" && window.localStorage.getItem(COLLAPSED_KEY) === "1";
  } catch {
    return false;
  }
}

function Brand({ name, light, compact = false }: { name: string; light: boolean; compact?: boolean }) {
  return (
    <span className={`flex items-center gap-2 font-heading font-bold tracking-tight ${light ? "text-white" : "text-[#1b2a55]"}`}>
      <span
        className={`grid size-8 place-items-center rounded-xl shadow ${
          light ? "bg-white/15 text-white ring-1 ring-white/25" : "bg-gradient-to-br from-[#2a3f86] to-[#16224f] text-white"
        }`}
      >
        <ShieldCheck aria-hidden className="size-4.5" />
      </span>
      <span className={compact ? "md:sr-only" : ""}>{name}</span>
    </span>
  );
}

function initials(name: string) {
  return name
    .split(" ")
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join("");
}

export function AppShell({ children }: { children: ReactNode }) {
  const { data: me } = useMe();
  const pathname = usePathname();
  // The organisation's manufacturing model decides whether BOMs, recipes or both appear.
  const { data: manufacturing } = useQuery({
    queryKey: ["/manufacturing/settings"],
    queryFn: () => api.get<ManufacturingSettings>("/manufacturing/settings"),
    enabled: Boolean(me?.permissions.includes("MANUFACTURING_VIEW") && me.hasSubscription),
  });
  const router = useRouter();
  const [menuOpen, setMenuOpen] = useState(false);
  // Collapsing applies from the md breakpoint up; on a phone the menu opens in full.
  const [collapsed, setCollapsed] = useState(storedCollapsed);

  const controlCenter = pathname === "/platform" || pathname.startsWith("/platform/");

  useEffect(() => {
    if (me && leavesWorkspace(pathname, me.permissions, me.hasSubscription)) {
      router.replace("/platform/dashboard");
    } else if (me && !me.onboarded && !controlCenter && me.permissions.includes("TENANT_MANAGE")) {
      router.replace("/onboarding");
    }
  }, [me, pathname, controlCenter, router]);

  async function switchOrganization(tenantId: string) {
    await session.post("switch-organization", { tenantId });
    hardNavigate("/dashboard");
  }

  function toggleCollapsed(next = !collapsed) {
    setCollapsed(next);
    try {
      window.localStorage.setItem(COLLAPSED_KEY, next ? "1" : "0");
    } catch {
      // Not remembered, but the menu still works.
    }
  }

  function openSearch() {
    toggleCollapsed(false);
    requestAnimationFrame(() => document.querySelector<HTMLInputElement>('[aria-label="Search everything"]')?.focus());
  }

  async function signOut() {
    await session.post("logout", {});
    hardNavigate("/login");
  }

  const link = (href: string, active: boolean) =>
    `flex items-center gap-2.5 rounded-xl px-3 py-2 text-sm font-medium transition ${collapsed ? "md:justify-center md:px-0" : ""} ${
      LINK[controlCenter ? "center" : "workspace"][active ? "on" : "off"]
    }`;
  /** The label beside an icon; hidden but still read out while the menu is collapsed. */
  const label = (text: string) => <span className={collapsed ? "md:sr-only" : ""}>{text}</span>;
  const hideWhenCollapsed = collapsed ? "md:hidden" : "";

  if (!me) {
    return <p className="p-6 text-sm text-muted-foreground">Loading…</p>;
  }

  const workspaceItems = forManufacturingModel(
    workspaceNavItems(me.permissions, me.hasSubscription),
    manufacturing?.manufacturingModel,
  );
  const activeWorkspaceHref = activeNavHref(pathname, workspaceItems);

  return (
    <div className="flex min-h-svh flex-col md:flex-row md:gap-6 md:p-4 print:block print:p-0">
      <header className="sticky top-0 z-30 flex items-center justify-between border-b border-white/70 bg-white/70 px-4 py-3 backdrop-blur-lg md:hidden print:hidden">
        <Brand name={controlCenter ? "Control Center" : "ProductionOS"} light={false} />
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
        className={`${menuOpen ? "flex" : "hidden"} m-3 flex-col gap-4 rounded-3xl ${
          controlCenter
            ? "bg-gradient-to-b from-[#24367a] to-[#121c45] text-slate-100 shadow-[0_30px_60px_-24px_rgba(22,34,79,0.7)]"
            : "glass"
        } p-4 *:shrink-0 md:sticky md:top-4 md:m-0 md:flex md:h-[calc(100svh-2rem)] md:shrink-0 md:overflow-y-auto md:transition-[width] print:hidden ${
          collapsed ? "md:w-[4.75rem] md:px-3" : "md:w-64"
        }`}
      >
        <div className={`hidden items-center justify-between gap-1 md:flex ${collapsed ? "md:flex-col md:gap-2" : ""}`}>
          <Brand name={controlCenter ? "Control Center" : "ProductionOS"} light={controlCenter} compact={collapsed} />
          <span className={`flex items-center gap-1 ${collapsed ? "flex-col" : ""}`}>
            <NotificationBell />
            <Button
              variant="ghost"
              size="icon"
              aria-label={collapsed ? "Expand menu" : "Collapse menu"}
              aria-expanded={!collapsed}
              title={collapsed ? "Expand menu" : "Collapse menu"}
              className={controlCenter ? "text-slate-200 hover:bg-white/10 hover:text-white" : ""}
              onClick={() => toggleCollapsed()}
            >
              {collapsed ? <PanelLeftOpen /> : <PanelLeftClose />}
            </Button>
          </span>
        </div>

        {me.organizations.length > 1 ? (
          <select
            aria-label="Organisation"
            className={`h-9 w-full rounded-xl border bg-white/70 px-2 text-sm ${hideWhenCollapsed}`}
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
          <p className={`truncate rounded-xl px-3 py-2 text-xs font-medium ${hideWhenCollapsed} ${controlCenter ? "bg-white/10 text-slate-300" : "bg-white/60 text-muted-foreground ring-1 ring-white/80"}`}>{me.organizations[0]?.name}</p>
        )}

        {!controlCenter && me.permissions.includes("TRACEABILITY_VIEW") && (
          <>
            <div className={hideWhenCollapsed}>
              <GlobalSearch />
            </div>
            {collapsed && (
              <Button variant="ghost" size="icon" title="Search" aria-label="Search" className="mx-auto hidden md:inline-flex" onClick={openSearch}>
                <Search />
              </Button>
            )}
          </>
        )}

        {controlCenter ? (
          <nav aria-label="Control Center" className="flex flex-1 flex-col gap-4">
            {CONTROL_CENTER_NAV.map(({ heading, items }) => (
              <div key={heading} className="space-y-1">
                <p className={`px-2 text-xs font-medium tracking-wide text-slate-400 uppercase ${collapsed ? "md:sr-only" : ""}`}>
                  {heading}
                </p>
                {collapsed && <hr aria-hidden className="mx-2 hidden border-white/15 md:block" />}
                {visibleNavItems(me?.permissions ?? [], items).map(({ href, label: text, icon: Icon }) => (
                  <Link
                    key={href}
                    href={href}
                    aria-current={pathname.startsWith(href) ? "page" : undefined}
                    title={collapsed ? text : undefined}
                    onClick={() => setMenuOpen(false)}
                    className={link(href, pathname.startsWith(href))}
                  >
                    <Icon aria-hidden className="size-4 shrink-0" />
                    {label(text)}
                  </Link>
                ))}
              </div>
            ))}
            <Link
              href={me.hasSubscription ? "/dashboard" : "/settings"}
              title={collapsed ? (me.hasSubscription ? "Back to workspace" : "Account settings") : undefined}
              className={link("/dashboard", false)}
            >
              <ArrowLeft aria-hidden className="size-4 shrink-0" />
              {label(me.hasSubscription ? "Back to workspace" : "Account settings")}
            </Link>
          </nav>
        ) : (
          <nav aria-label="Main" className="flex flex-1 flex-col gap-1">
            {workspaceItems.map(({ href, label: text, icon: Icon, group }, index) => {
              const active = href === activeWorkspaceHref;
              const startsGroup = group && workspaceItems[index - 1]?.group !== group;
              const endsGroup = group && workspaceItems[index + 1]?.group !== group;
              return (
                <Fragment key={href}>
                {startsGroup && (
                  <p className={`mt-2 px-3 pt-1 text-xs font-medium tracking-wide text-muted-foreground uppercase ${collapsed ? "md:sr-only" : ""}`}>
                    {group}
                  </p>
                )}
                <Link
                  href={href}
                  aria-current={active ? "page" : undefined}
                  title={collapsed ? text : undefined}
                  onClick={() => setMenuOpen(false)}
                  className={link(href, active)}
                >
                  <Icon aria-hidden className="size-4 shrink-0" />
                  {label(text)}
                </Link>
                {endsGroup && <hr aria-hidden className="mx-3 my-1 border-[#1b2a55]/10" />}
                </Fragment>
              );
            })}
          </nav>
        )}

        <div className={`space-y-2 border-t pt-3 ${controlCenter ? "border-white/15" : "border-[#1b2a55]/10"}`}>
          <p className={`flex items-center gap-2 truncate text-sm font-medium ${collapsed ? "md:justify-center" : ""}`} title={collapsed ? me.user.fullName : undefined}>
            <span
              aria-hidden
              className={`grid size-8 shrink-0 place-items-center rounded-full text-xs font-semibold ${
                controlCenter ? "bg-white/15 text-white" : "bg-white text-[#1b2a55] shadow ring-1 ring-white"
              }`}
            >
              {initials(me.user.fullName)}
            </span>
            <span className={`truncate ${collapsed ? "md:sr-only" : ""}`}>{me.user.fullName}</span>
          </p>
          <Button
            variant="ghost"
            size="sm"
            title={collapsed ? "Sign out" : undefined}
            className={`w-full justify-start ${collapsed ? "md:justify-center" : ""} ${
              controlCenter ? "text-slate-200 hover:bg-white/10 hover:text-white" : ""
            }`}
            onClick={signOut}
          >
            <LogOut aria-hidden />
            {label("Sign out")}
          </Button>
        </div>
      </aside>

      <main className="min-w-0 flex-1 p-4 md:py-4 md:pr-4 md:pl-0">
        {!me.user.emailVerified && <VerifyEmailBanner email={me.user.email} />}
        {!controlCenter && <PlatformNotices />}
        {me.hasSubscription && <SubscriptionBanner />}
        {children}
      </main>
    </div>
  );
}
