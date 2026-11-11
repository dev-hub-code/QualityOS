"use client";

import { FormError } from "@/components/forms/field";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import { useOrganizationSettings, useSaveOrganizationSettings } from "@/hooks/use-organization-settings";
import { usePermission } from "@/hooks/use-me";

/** The settings section for reviewing one's own work in a one-person organisation. */
export function ReviewPolicySettings() {
  const canManage = usePermission("TENANT_MANAGE");
  const { data } = useOrganizationSettings();
  const update = useSaveOrganizationSettings();
  if (!data) return null;
  return (
    <section className="space-y-3">
      <h2 className="text-base font-semibold text-[#1b2a55]">Reviews</h2>
      <p className="text-sm text-muted-foreground">
        Normally a second person approves an inspection, closes an NCR and verifies a CAPA. If you work alone, you can
        do these yourself. You must give a reason each time, and it is saved in the audit log.
      </p>
      <FormError message={update.error?.message} />
      <div className="flex items-center gap-2">
        <Checkbox
          id="single-person-review"
          checked={data.singlePersonReview}
          disabled={!canManage || update.isPending}
          onCheckedChange={(checked) => update.mutate({ singlePersonReview: checked === true })}
        />
        <Label htmlFor="single-person-review" className="font-normal">
          Let me review my own work while I am the only member
        </Label>
      </div>
      {data.singlePersonReview && !data.selfReviewInEffect && (
        <p className="text-sm text-muted-foreground">
          This is paused because your organisation has {data.activeMembers} active members. Reviews need a second
          person again.
        </p>
      )}
    </section>
  );
}

/** Shown where a sole member is about to review their own work. */
export function SelfReviewNotice() {
  return (
    <p className="text-sm text-muted-foreground">
      You are reviewing your own work because you are the only member. Your note is saved in the audit log.
    </p>
  );
}
