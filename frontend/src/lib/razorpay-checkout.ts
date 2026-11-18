/** Razorpay's own checkout, loaded only when someone pays, so card and UPI details never reach ProductionOS. */

const SCRIPT = "https://checkout.razorpay.com/v1/checkout.js";

export interface RazorpayOptions {
  key: string;
  order_id: string;
  amount: number;
  currency: string;
  name: string;
  description: string;
  prefill: { name?: string; email?: string };
  theme: { color: string };
  handler: () => void;
  modal: { ondismiss: () => void };
}

interface RazorpayInstance {
  open(): void;
  on(event: "payment.failed", listener: () => void): void;
}

declare global {
  interface Window {
    Razorpay?: new (options: RazorpayOptions) => RazorpayInstance;
  }
}

let loading: Promise<void> | null = null;

/** Loads the script once. A failed load can be tried again. */
export function loadRazorpay(): Promise<void> {
  if (window.Razorpay) return Promise.resolve();
  loading ??= new Promise<void>((resolve, reject) => {
    const script = document.createElement("script");
    script.src = SCRIPT;
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () => {
      loading = null;
      script.remove();
      reject(new Error("The payment page could not be loaded. Check the connection and try again."));
    };
    document.body.appendChild(script);
  });
  return loading;
}

/**
 * Opens checkout. Settles when the person pays or closes it: either way the caller asks the server what happened,
 * because nothing the browser reports about a payment is believed.
 */
export async function payWithRazorpay(
  options: Omit<RazorpayOptions, "handler" | "modal" | "theme">,
): Promise<"paid" | "closed"> {
  await loadRazorpay();
  const Razorpay = window.Razorpay;
  if (!Razorpay) throw new Error("The payment page could not be loaded. Please try again.");
  return new Promise((resolve) => {
    const checkout = new Razorpay({
      ...options,
      theme: { color: "#1b2a55" },
      handler: () => resolve("paid"),
      modal: { ondismiss: () => resolve("closed") },
    });
    checkout.open();
  });
}
