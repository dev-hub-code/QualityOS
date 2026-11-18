"use client";

import { PlatformNotices } from "@/components/app/platform-notices";
import { OfflineResults } from "@/components/inspections/offline-results";
import { queuedBy } from "@/lib/offline-results";
import { SubscriptionBanner } from "@/components/billing/subscription-banner";
import { ArrowLeft, ChevronDown, ChevronsLeft, LogOut, Menu, Search, ShieldCheck, X } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { Fragment, useEffect, useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { GlobalSearch } from "@/components/app/global-search";
import { OrganizationLogo } from "@/components/app/organization-logo";
import { NotificationBell, useNotificationStream } from "@/components/app/notification-bell";
import { SupportSessionBanner } from "@/components/app/support-session-banner";
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
  navSections,
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
const CLOSED_GROUPS_KEY = "qualityos.menu-closed-groups";

/** The menu groups someone closed, remembered per browser like the collapsed menu. */
function storedClosedGroups(): string[] {
  try {
    const stored = typeof window !== "undefined" ? JSON.parse(window.localStorage.getItem(CLOSED_GROUPS_KEY) ?? "[]") : [];
    return Array.isArray(stored) ? stored.filter((group): group is string => typeof group === "string") : [];
  } catch {
    return [];
  }
}

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
  const [closedGroups, setClosedGroups] = useState(storedClosedGroups);

  const controlCenter = pathname === "/platform" || pathname.startsWith("/platform/");
  // Only a member of an organisation has an inbox.
  useNotificationStream(Boolean(me?.tenantId));

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

  function toggleGroup(group: string) {
    const next = closedGroups.includes(group) ? closedGroups.filter((closed) => closed !== group) : [...closedGroups, group];
    setClosedGroups(next);
    try {
      window.localStorage.setItem(CLOSED_GROUPS_KEY, JSON.stringify(next));
    } catch {
      // Not remembered, but the menu still works.
    }
  }

  function openAllGroups() {
    setClosedGroups([]);
    try {
      window.localStorage.removeItem(CLOSED_GROUPS_KEY);
    } catch {
      // Not remembered, but the menu still works.
    }
  }

  function openSearch() {
    toggleCollapsed(false);
    requestAnimationFrame(() => document.querySelector<HTMLInputElement>('[aria-label="Search everything"]')?.focus());
  }

  async function signOut() {
    // Results kept offline are sent only by the person who saved them, so say so before they leave.
    const waiting = me ? queuedBy({ userId: me.user.id, tenantId: me.tenantId }) : [];
    if (
      waiting.length > 0 &&
      !window.confirm(
        `${waiting.map((item) => item.inspectionNumber).join(", ")} ${waiting.length === 1 ? "has" : "have"} inspection values not sent yet. They stay on this device and are sent only when you sign in here again. Sign out anyway?`,
      )
    ) {
      return;
    }
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
  const organizationName =
    me.support?.organizationName ?? me.organizations.find((organization) => organization.id === me.tenantId)?.name ?? "Organisation";
  const workspaceSections = navSections(workspaceItems);
  const anyGroupClosed = workspaceSections.some(({ group }) => group && closedGroups.includes(group));

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
          {collapsed ? (
            // In the narrow menu, the logo is also a way back to the full one.
            <button type="button" aria-label="ProductionOS, expand menu" title="Expand menu" className="rounded-xl" onClick={() => toggleCollapsed(false)}>
              <Brand name={controlCenter ? "Control Center" : "ProductionOS"} light={controlCenter} compact />
            </button>
          ) : (
            <Brand name={controlCenter ? "Control Center" : "ProductionOS"} light={controlCenter} />
          )}
          <span className={`flex items-center gap-1 ${collapsed ? "flex-col" : ""}`}>
            <NotificationBell />
          </span>
        </div>

        <div
          className={`flex items-center gap-2.5 rounded-xl p-2 ${hideWhenCollapsed} ${
            controlCenter ? "bg-white/10" : me.support ? "bg-amber-50 ring-1 ring-amber-200" : "bg-white/60 ring-1 ring-white/80"
          }`}
        >
          {!controlCenter && me.tenantId && <OrganizationLogo tenantId={me.tenantId} name={organizationName} />}
          {me.support ? (
            <p className="min-w-0 flex-1 text-sm leading-tight font-semibold text-amber-900">
              <span className="block text-[11px] font-medium tracking-wide uppercase">Support view</span>
              <span className="block truncate" title={me.support.organizationName}>
                {me.support.organizationName}
              </span>
            </p>
          ) : me.organizations.length > 1 ? (
            <select
              aria-label="Organisation"
              className="h-9 min-w-0 flex-1 rounded-lg border bg-white/70 px-2 text-sm font-medium"
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
            <p
              className={`line-clamp-2 min-w-0 flex-1 text-sm leading-tight font-semibold break-words ${controlCenter ? "text-slate-200" : "text-[#1b2a55]"}`}
              title={organizationName}
            >
              {organizationName}
            </p>
          )}
        </div>

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
            {anyGroupClosed && !collapsed && (
              <button
                type="button"
                onClick={openAllGroups}
                className="self-end rounded-lg px-2 py-0.5 text-xs font-medium text-primary hover:bg-white/70 hover:underline"
              >
                Open all
              </button>
            )}
            {workspaceSections.map(({ group, items }) => {
              const navLink = ({ href, label: text, icon: Icon }: (typeof items)[number]) => (
                <Link
                  key={href}
                  href={href}
                  aria-current={href === activeWorkspaceHref ? "page" : undefined}
                  title={collapsed ? text : undefined}
                  onClick={() => setMenuOpen(false)}
                  className={link(href, href === activeWorkspaceHref)}
                >
                  <Icon aria-hidden className="size-4 shrink-0" />
                  {label(text)}
                </Link>
              );
              if (!group) {
                return <Fragment key="ungrouped">{items.map(navLink)}</Fragment>;
              }
              // The group of the page you are on stays open, so the highlighted item is never hidden.
              const holdsActive = items.some(({ href }) => href === activeWorkspaceHref);
              const open = collapsed || holdsActive || !closedGroups.includes(group);
              const id = `menu-${group.toLowerCase().replaceAll(" ", "-")}`;
              return (
                <div key={group} className="mt-1">
                  {collapsed && <hr aria-hidden className="mx-2 my-1 hidden border-[#1b2a55]/10 md:block" />}
                  <button
                    type="button"
                    aria-expanded={open}
                    title={holdsActive ? undefined : open ? `Hide ${group}` : `Show ${group}`}
                    aria-controls={id}
                    disabled={holdsActive}
                    onClick={() => toggleGroup(group)}
                    className={`flex w-full items-center justify-between rounded-lg px-3 pt-2 pb-1 text-left text-xs font-semibold tracking-wide text-muted-foreground uppercase hover:text-[#1b2a55] disabled:cursor-default disabled:hover:text-muted-foreground ${collapsed ? "md:sr-only" : ""}`}
                  >
                    <span>{group}</span>
                    <span className="flex items-center gap-1.5">
                      {!open && (
                        <span
                          title={`${items.length} hidden`}
                          className="grid h-4 min-w-4 place-items-center rounded-full bg-[#1b2a55] px-1 text-[10px] font-semibold text-white"
                        >
                          {items.length}
                          <span className="sr-only"> hidden</span>
                        </span>
                      )}
                      <ChevronDown aria-hidden className={`size-3.5 transition-transform ${open ? "" : "-rotate-90"}`} />
                    </span>
                  </button>
                  <div id={id} hidden={!open} className="space-y-0.5">
                    {items.map(navLink)}
                  </div>
                </div>
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

      {/* On the menu's edge: << collapses it to icons, >> brings the names back. */}
      <div className="relative hidden w-0 md:-ml-6 md:block print:hidden">
        <button
          type="button"
          aria-label={collapsed ? "Expand menu" : "Collapse menu"}
          aria-expanded={!collapsed}
          onClick={() => toggleCollapsed()}
          className={`group/toggle sticky top-[calc(50svh-1rem)] z-20 grid size-8 -translate-x-1/2 place-items-center rounded-full border backdrop-blur-md transition-all duration-200 hover:scale-110 active:scale-95 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#2f4590] ${
            controlCenter
              ? "border-white/25 bg-[#1c2a62]/80 text-white shadow-[0_6px_16px_-6px_rgba(0,0,0,0.6)] hover:bg-white hover:text-[#1b2a55]"
              : "border-white bg-white/80 text-[#1b2a55] shadow-[0_6px_18px_-6px_rgba(27,42,85,0.45)] hover:border-transparent hover:bg-gradient-to-b hover:from-[#2f4590] hover:to-[#1c2a62] hover:text-white hover:shadow-[0_10px_22px_-8px_rgba(28,42,98,0.8)]"
          }`}
        >
          {/* One icon that turns round: « while open, » once collapsed. */}
          <ChevronsLeft aria-hidden className={`size-4 transition-transform duration-300 ease-out ${collapsed ? "rotate-180" : ""}`} />
          <span
            aria-hidden
            className="pointer-events-none absolute left-full ml-2 -translate-x-1 rounded-lg bg-[#1b2a55] px-2 py-1 text-xs font-medium whitespace-nowrap text-white opacity-0 shadow-lg transition-all duration-200 group-hover/toggle:translate-x-0 group-hover/toggle:opacity-100 group-focus-visible/toggle:translate-x-0 group-focus-visible/toggle:opacity-100"
          >
            {collapsed ? "Expand menu" : "Collapse menu"}
          </span>
        </button>
      </div>

      <main className="min-w-0 flex-1 p-4 md:py-4 md:pr-4 md:pl-0">
        {me.support && <SupportSessionBanner organizationName={me.support.organizationName} expiresAt={me.support.expiresAt} allowChanges={me.support.allowChanges} />}
        {!me.user.emailVerified && <VerifyEmailBanner email={me.user.email} />}
        {!controlCenter && <PlatformNotices />}
        {me.hasSubscription && <SubscriptionBanner />}
        {!controlCenter && <OfflineResults />}
        {children}
      </main>
    </div>
  );
}
