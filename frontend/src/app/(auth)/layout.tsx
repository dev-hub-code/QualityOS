import { ShieldCheck } from "lucide-react";

export default function AuthLayout({ children }: LayoutProps<"/">) {
  return (
    <main className="mx-auto flex w-full max-w-md flex-1 flex-col justify-center gap-6 px-4 py-12">
      <header className="space-y-3">
        <p className="flex items-center gap-2 font-heading text-xl font-bold tracking-tight text-[#1b2a55]">
          <span className="grid size-9 place-items-center rounded-xl bg-gradient-to-br from-[#2a3f86] to-[#16224f] text-white shadow">
            <ShieldCheck aria-hidden className="size-5" />
          </span>
          ProductionOS
        </p>
        <p className="text-sm text-muted-foreground">
          Digital Quality. Traceable Products. <span className="text-slate-400">Confident Manufacturing.</span>
        </p>
      </header>
      <div className="glass rounded-3xl p-6 sm:p-8 [&_h1]:text-2xl [&_h1]:font-bold [&_h1]:tracking-tight [&_h1]:text-[#1b2a55]">
        {children}
      </div>
    </main>
  );
}
