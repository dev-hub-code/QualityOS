/** The package ships no types. This declares the part of it that QualityOS calls. */
declare module "@cashfreepayments/cashfree-js" {
  interface Cashfree {
    checkout(options: { paymentSessionId: string; redirectTarget?: "_self" }): Promise<unknown>;
  }

  export function load(options: { mode: "sandbox" | "production" }): Promise<Cashfree>;
}
