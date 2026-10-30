import { SystemStatus } from "@/components/system/system-status";

export default function Home() {
  return (
    <main className="mx-auto flex w-full max-w-md flex-1 flex-col justify-center gap-8 px-4 py-12">
      <header className="space-y-2">
        <h1 className="font-heading text-3xl font-semibold tracking-tight text-primary">
          QualityOS
        </h1>
        <p className="text-muted-foreground">
          Digital Quality. Traceable Products. Confident Manufacturing.
        </p>
      </header>
      <SystemStatus />
    </main>
  );
}
