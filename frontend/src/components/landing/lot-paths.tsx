import { Ban, Boxes, Check, ClipboardCheck, PackageCheck, Truck, UserCheck } from "lucide-react";

const PASSED = [
  { icon: PackageCheck, kind: "Received", value: "LOT-0412" },
  { icon: ClipboardCheck, kind: "Inspection", value: "Passed" },
  { icon: Boxes, kind: "Batch", value: "BT-0097" },
  { icon: Truck, kind: "Dispatch", value: "Customer named" },
];

/** The product's central promise, drawn: one lot goes on to a customer, and the other cannot. */
export function LotPaths() {
  return (
    <figure className="relative mx-auto w-full max-w-md pt-8 pb-16 sm:pb-10 lg:max-w-none">
      <div aria-hidden className="absolute inset-x-6 top-10 bottom-8 -z-10 rounded-[2rem] bg-[#2a3f86]/30 blur-3xl" />

      <div className="relative rotate-[-2deg] rounded-3xl bg-gradient-to-br from-[#2d4593] via-[#1f2f6b] to-[#141f4a] p-6 pb-28 text-white shadow-[0_30px_60px_-20px_rgba(20,31,74,0.65)] ring-1 ring-white/15 transition-transform duration-500 hover:rotate-0">
        <div aria-hidden className="pointer-events-none absolute inset-0 rounded-3xl bg-[radial-gradient(30rem_14rem_at_0%_0%,rgba(255,255,255,0.18),transparent_60%)]" />
        <div className="relative flex items-center justify-between">
          <p className="text-sm font-semibold tracking-tight">A lot that passes</p>
          <span className="inline-flex items-center gap-1 rounded-full bg-emerald-400/15 px-2.5 py-1 text-xs font-medium text-emerald-200 ring-1 ring-emerald-300/30">
            <Check className="size-3" /> Released
          </span>
        </div>
        <p className="relative mt-4 text-xs text-white/60">Lot</p>
        <p className="relative text-3xl font-semibold tracking-tight tabular-nums">LOT-0412</p>
        <div aria-hidden className="relative top-[2.95rem] mx-[12.5%] h-px bg-white/30" />
        <ol className="relative mt-6 grid grid-cols-4 gap-2">
          {PASSED.map(({ icon: Icon, kind, value }) => (
            <li key={kind} className="relative flex flex-col items-center text-center">
              <span className="grid size-11 place-items-center rounded-2xl bg-[#2a3d82] shadow-inner ring-1 ring-white/20 backdrop-blur">
                <Icon className="size-5" />
              </span>
              <span className="mt-2 text-[0.7rem] text-white/60">{kind}</span>
              <span className="text-xs font-medium leading-tight">{value}</span>
            </li>
          ))}
        </ol>
      </div>

      <div className="glass absolute right-0 bottom-8 w-64 sm:bottom-0 rotate-[3deg] rounded-2xl p-4 sm:-right-4">
        <div className="flex items-center gap-3">
          <span className="grid size-9 shrink-0 place-items-center rounded-xl bg-destructive/10 text-destructive">
            <Ban className="size-4" />
          </span>
          <div>
            <p className="text-xs text-muted-foreground">A lot that fails</p>
            <p className="text-sm font-semibold">LOT-0413 · Failed</p>
          </div>
        </div>
        <dl className="mt-3 grid grid-cols-2 gap-2 text-xs">
          <div className="rounded-lg bg-destructive/8 px-2 py-1.5">
            <dt className="text-muted-foreground">Held</dt>
            <dd className="font-medium text-destructive">Not released</dd>
          </div>
          <div className="rounded-lg bg-white/70 px-2 py-1.5">
            <dt className="text-muted-foreground">NCR</dt>
            <dd className="font-medium">CAPA raised</dd>
          </div>
        </dl>
      </div>

      <div className="glass absolute -top-1 left-0 hidden rotate-[-4deg] items-center gap-2 rounded-2xl px-3 py-2 sm:flex lg:-left-6">
        <span className="grid size-7 place-items-center rounded-lg bg-success/15 text-success">
          <UserCheck className="size-4" />
        </span>
        <span className="text-xs font-medium">Approved by a second person</span>
      </div>

      <figcaption className="absolute bottom-0 left-0 text-xs text-muted-foreground">Example records, for illustration.</figcaption>
    </figure>
  );
}
