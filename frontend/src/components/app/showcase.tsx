"use client";

import type { ReactNode } from "react";
import Link from "next/link";
import { ShieldCheck, type LucideIcon } from "lucide-react";

/** Navy on frosted silver-blue, after docs/reference.jpeg. Shared by the workspace and Control Center dashboards. */

export const NAVY = "bg-gradient-to-br from-[#2a3f86] to-[#16224f]";

export interface QuickAction {
  label: string;
  href: string;
  icon: LucideIcon;
}

/** The silver-blue panel that frames a dashboard. */
export function DashboardFrame({ children }: { children: ReactNode }) {
  return (
    <div className="relative isolate overflow-hidden rounded-3xl bg-gradient-to-br from-slate-100 via-[#e4e9f1] to-[#c9d3e1] p-4 ring-1 ring-slate-300/60 sm:p-6 lg:p-8">
      <div aria-hidden className="pointer-events-none absolute -top-32 -left-24 -z-10 size-96 rounded-full bg-white/70 blur-3xl" />
      <div aria-hidden className="pointer-events-none absolute -right-24 -bottom-40 -z-10 size-[28rem] rounded-full bg-[#9fb0cc]/40 blur-3xl" />
      {children}
    </div>
  );
}

/** A pill badge over a large two-tone heading: `lead` in navy, `accent` in grey. */
export function HeroHeading({ badge, lead, accent, children }: { badge: string; lead: string; accent?: string; children?: ReactNode }) {
  return (
    <div className="space-y-5">
      <span className="inline-flex items-center gap-2 rounded-full bg-white/70 px-3 py-1 text-xs font-medium text-[#1e2d5a] shadow-sm ring-1 ring-white/80 backdrop-blur">
        <span className="size-2 rounded-full bg-[#1e2d5a]" />
        {badge}
      </span>
      <h1 className="text-3xl leading-tight font-bold tracking-tight text-[#1b2a55] sm:text-4xl lg:text-5xl">
        {lead}
        {accent && (
          <>
            {" "}
            <span className="bg-gradient-to-r from-slate-500 to-slate-400 bg-clip-text text-transparent">{accent}</span>
          </>
        )}
      </h1>
      {children}
    </div>
  );
}

/** The tilted navy card with one large figure, round quick actions and up to two cards floating at its side. */
export function HeroCard({
  brand,
  badge,
  label,
  value,
  note,
  actions,
  floats,
}: {
  brand: string;
  badge: string;
  label: ReactNode;
  value: string | number;
  note: ReactNode;
  actions: QuickAction[];
  floats: ReactNode;
}) {
  return (
    <div className="relative mx-auto w-full max-w-md lg:mr-28 lg:ml-0 lg:max-w-sm">
      <div className="relative overflow-hidden rounded-3xl bg-gradient-to-br from-[#2a3f86] via-[#1f2f6b] to-[#16224f] p-6 text-white shadow-[0_30px_60px_-20px_rgba(22,34,79,0.6)] ring-1 ring-white/15 lg:-rotate-2">
        <div aria-hidden className="absolute -top-20 -right-16 size-56 rounded-full bg-white/10 blur-2xl" />
        <div className="relative flex items-center justify-between">
          <span className="flex items-center gap-2 text-sm font-semibold">
            <ShieldCheck className="size-5" /> {brand}
          </span>
          <span className="rounded-full bg-white/15 px-2.5 py-0.5 text-[11px] text-white/80 ring-1 ring-white/20">{badge}</span>
        </div>
        <p className="relative mt-6 text-xs text-white/70">{label}</p>
        <p
          className={`relative font-bold tracking-tight break-words tabular-nums lg:pr-10 ${
            String(value).length > 8 ? "text-3xl sm:text-4xl" : "text-4xl sm:text-5xl"
          }`}
        >
          {value}
        </p>
        <p className="relative mt-1 text-xs text-white/60">{note}</p>
        <nav aria-label="Quick actions" className="relative mt-6 grid grid-cols-4 gap-2 lg:pr-12">
          {actions.map(({ label: name, href, icon: Icon }) => (
            <Link
              key={href}
              href={href}
              className="group flex flex-col items-center gap-1.5 rounded-xl outline-none focus-visible:ring-2 focus-visible:ring-white/70"
            >
              <span className="grid size-11 place-items-center rounded-xl bg-white/90 text-[#1f2f6b] shadow-md transition group-hover:-translate-y-0.5 group-hover:bg-white">
                <Icon className="size-5" />
              </span>
              <span className="text-[11px] text-white/85">{name}</span>
            </Link>
          ))}
        </nav>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-3 lg:absolute lg:top-10 lg:-right-28 lg:mt-0 lg:flex lg:w-40 lg:flex-col lg:gap-4">
        {floats}
      </div>
    </div>
  );
}

export function FloatCard({
  icon: Icon,
  label,
  value,
  note,
  href,
  alert = false,
}: {
  icon: LucideIcon;
  label: string;
  value: ReactNode;
  note: string;
  href: string;
  alert?: boolean;
}) {
  return (
    <Link
      href={href}
      className="min-w-0 rounded-2xl bg-gradient-to-br from-[#2b3f84]/95 to-[#1a275c]/95 p-3 text-white shadow-[0_18px_40px_-16px_rgba(22,34,79,0.7)] ring-1 ring-white/20 backdrop-blur transition hover:-translate-y-0.5"
    >
      <span className="flex items-center gap-2 text-[11px] text-white/75">
        <span className={`grid size-6 shrink-0 place-items-center rounded-md ${alert ? "bg-red-400/30" : "bg-white/15"}`}>
          <Icon className="size-3.5" />
        </span>
        {label}
      </span>
      <span className="mt-1 block truncate text-xl font-semibold tabular-nums">{value}</span>
      <span className="text-[11px] text-white/60">{note}</span>
    </Link>
  );
}

/** A count with an icon, linking to where the work waits. */
export function Pending({ icon: Icon, label, value, href }: { icon: LucideIcon; label: string; value: ReactNode; href: string }) {
  return (
    <Link href={href} className="group flex items-center gap-3 rounded-xl px-1 py-1 outline-none focus-visible:ring-2 focus-visible:ring-ring">
      <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-white/70 text-[#1e2d5a] shadow-sm ring-1 ring-white/80 transition group-hover:bg-white">
        <Icon className="size-5" />
      </span>
      <span className="text-sm leading-tight text-slate-600">
        <span className="block text-lg font-semibold text-[#1b2a55] tabular-nums">{value}</span>
        {label}
      </span>
    </Link>
  );
}

/** A figure tile for a `<dl>`. */
export function Tile({ icon: Icon, label, value }: { icon: LucideIcon; label: string; value: ReactNode }) {
  return (
    <div className="glass flex flex-col gap-3 rounded-2xl p-4 sm:flex-row sm:items-center">
      <span className={`grid size-10 shrink-0 place-items-center rounded-xl ${NAVY} text-white shadow`}>
        <Icon className="size-5" />
      </span>
      <div className="min-w-0">
        <dt className="truncate text-xs text-slate-500">{label}</dt>
        <dd className="text-xl font-semibold break-words text-[#1b2a55] tabular-nums sm:text-2xl">{value}</dd>
      </div>
    </div>
  );
}

export function Panel({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <section className="glass rounded-2xl p-5">
      <div className="mb-4 flex items-center justify-between gap-2">
        <h2 className="text-base font-semibold text-[#1b2a55]">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}
