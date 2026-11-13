import type { Metadata } from "next";
import Link from "next/link";
import { DocsNav } from "@/components/docs/docs-nav";
import { buttonVariants } from "@/components/ui/button";

export const metadata: Metadata = {
  title: { default: "Documentation | ProductionOS", template: "%s | ProductionOS docs" },
  description: "How each part of ProductionOS works, with a worked example.",
};

export default function DocsLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <header className="border-b">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-x-4 gap-y-2 px-4 py-3 sm:px-6">
          <div className="flex items-baseline gap-3">
            <Link href="/" className="text-lg font-semibold text-primary">
              ProductionOS
            </Link>
            <Link href="/docs" className="text-sm text-muted-foreground hover:text-foreground">
              Documentation
            </Link>
          </div>
          <Link href="/login" className={buttonVariants({ variant: "outline", size: "sm" })}>
            Open ProductionOS
          </Link>
        </div>
      </header>
      <div className="mx-auto grid max-w-6xl gap-8 px-4 py-8 sm:px-6 lg:grid-cols-[15rem_minmax(0,1fr)]">
        <DocsNav />
        <main className="min-w-0">{children}</main>
      </div>
    </div>
  );
}
