"use client";

import QRCode from "qrcode";
import { useState, type FormEvent } from "react";
import { Field, FormError } from "@/components/forms/field";
import { PageHeader } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { api, ApiError } from "@/lib/api";
import type { SecondFactorEnrolment } from "@/lib/types";

function explain(failure: unknown) {
  return failure instanceof ApiError ? failure.message : "Something went wrong. Please try again.";
}

/** Shown instead of the Control Center until the operator's authenticator app has confirmed a code. */
export function SecondFactorSetup({ onDone }: { onDone: () => void }) {
  const [enrolment, setEnrolment] = useState<SecondFactorEnrolment | null>(null);
  const [image, setImage] = useState("");
  const [code, setCode] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function start() {
    setError("");
    setCode("");
    setBusy(true);
    try {
      const next = await api.post<SecondFactorEnrolment>("/platform/second-factor/enrolment");
      setImage(await QRCode.toDataURL(next.otpauthUri, { margin: 1, width: 192 }));
      setEnrolment(next);
    } catch (failure) {
      setError(explain(failure));
    } finally {
      setBusy(false);
    }
  }

  async function confirm(event: FormEvent) {
    event.preventDefault();
    setError("");
    setBusy(true);
    try {
      await api.post("/platform/second-factor/enrolment/confirm", { code });
      onDone();
    } catch (failure) {
      setError(explain(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto max-w-lg space-y-6">
      <PageHeader
        title="Set up your authenticator app"
        subtitle="The Control Center asks for a code from it each time you sign in, as well as your password."
      />
      <Card>
        <CardContent className="space-y-4 pt-6">
          {!enrolment ? (
            <>
              <p className="text-sm text-muted-foreground">
                Install an authenticator app on your phone, such as Google Authenticator, Microsoft Authenticator or
                Aegis, then continue. You will scan a code with it.
              </p>
              <FormError message={error} />
              <Button onClick={start} disabled={busy}>
                Show the code to scan
              </Button>
            </>
          ) : (
            <form onSubmit={confirm} className="space-y-4" noValidate>
              <ol className="list-decimal space-y-3 pl-5 text-sm">
                <li>
                  <p>Scan this with your authenticator app.</p>
                  {/* A data URL made in the browser, so there is nothing for next/image to optimise. */}
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img
                    src={image}
                    alt="Code for your authenticator app"
                    width={192}
                    height={192}
                    className="mt-2 rounded-md bg-white p-1"
                  />
                  <p className="mt-2 text-muted-foreground">
                    Or type this key into it:{" "}
                    <code data-testid="second-factor-secret" className="font-mono tracking-wider">
                      {enrolment.secret.replace(/(.{4})/g, "$1 ").trim()}
                    </code>
                  </p>
                </li>
                <li>
                  <p className="mb-2">Enter the six-digit code the app shows for ProductionOS.</p>
                  <Field
                    label="Six-digit code from the app"
                    id="second-factor-code"
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    pattern="[0-9]{6}"
                    maxLength={6}
                    required
                    value={code}
                    onChange={(event) => setCode(event.target.value.replace(/\D/g, ""))}
                  />
                </li>
              </ol>
              <FormError message={error} />
              <div className="flex flex-wrap gap-2">
                <Button type="submit" disabled={busy || code.length !== 6}>
                  Confirm
                </Button>
                <Button type="button" variant="ghost" onClick={start} disabled={busy}>
                  Start again with a new key
                </Button>
              </div>
            </form>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
