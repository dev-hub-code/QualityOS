import { Check } from "lucide-react";
import Link from "next/link";
import { buttonVariants } from "@/components/ui/button";

export interface PublicPlan {
  code: string;
  name: string;
  description: string;
  monthlyPrice: number | null;
  annualPrice: number | null;
  maxUsers: number | null;
  maxFacilities: number | null;
  features: string[];
  trialDays: number;
  featureNames: string[];
}

const BASE = "Inspections, suppliers, purchasing and receipts";
const rupees = (value: number) => `₹${value.toLocaleString("en-IN", { maximumFractionDigits: 2 })}`;

const count = (value: number | null, noun: string) =>
  value === null ? `Unlimited ${noun}s` : `${value.toLocaleString("en-IN")} ${noun}${value === 1 ? "" : "s"}`;

function Feature({ children }: { children: string }) {
  return (
    <li className="flex gap-2">
      <Check aria-hidden className="mt-0.5 size-4 shrink-0 text-[#3b56b0]" />
      <span>{children}</span>
    </li>
  );
}

function Plan({ plan }: { plan: PublicPlan }) {
  return (
    <li className="glass flex flex-col rounded-3xl p-6 transition duration-300 hover:-translate-y-1 hover:shadow-[0_24px_50px_-20px_rgba(30,41,82,0.45)]">
      <h3 className="text-lg font-semibold">{plan.name}</h3>
      <p className="mt-1 min-h-10 text-sm text-muted-foreground">{plan.description}</p>
      {plan.monthlyPrice === null ? (
        <p className="mt-5 text-4xl font-bold tracking-tight">Custom</p>
      ) : (
        <p className="mt-5 text-4xl font-bold tracking-tight">
          {rupees(plan.monthlyPrice)}
          <span className="text-sm font-normal text-muted-foreground"> a month</span>
        </p>
      )}
      <p className="mt-1 h-5 text-sm text-muted-foreground">
        {plan.annualPrice !== null && `or ${rupees(plan.annualPrice)} a year`}
      </p>
      <ul className="mt-5 space-y-2 border-t border-[#1b2a55]/10 pt-5 text-sm">
        <Feature>{count(plan.maxUsers, "user")}</Feature>
        <Feature>{count(plan.maxFacilities, "facility").replace("facilitys", "facilities")}</Feature>
        <Feature>{BASE}</Feature>
        {plan.featureNames.map((feature) => (
          <Feature key={feature}>{feature}</Feature>
        ))}
      </ul>
    </li>
  );
}

/** Prices come from the plans a platform administrator keeps, and are never written into this page. */
export function Pricing({ plans }: { plans: PublicPlan[] | null }) {
  if (!plans || plans.length === 0) {
    return (
      <p className="text-muted-foreground">
        Prices cannot be shown right now. They are listed on the billing page once you have an account.
      </p>
    );
  }
  const trial = Math.max(...plans.map((plan) => plan.trialDays));
  return (
    <>
      <ul className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
        {plans.map((plan) => (
          <Plan key={plan.code} plan={plan} />
        ))}
      </ul>
      <div className="mt-10 flex flex-wrap items-center justify-center gap-4">
        {trial > 0 && (
          <Link href="/register" className={buttonVariants({ size: "lg" })}>
            Start a {trial}-day trial
          </Link>
        )}
        <p className="text-sm text-muted-foreground">Prices exclude GST. No card is needed to start.</p>
      </div>
    </>
  );
}
