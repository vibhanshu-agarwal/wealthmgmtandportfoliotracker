import { cn } from "@/lib/utils/cn";

export type PickerStep = "browse" | "review";

const STEPS: Array<{ key: PickerStep; label: string; ordinal: number }> = [
  { key: "browse", label: "Browse", ordinal: 1 },
  { key: "review", label: "Review", ordinal: 2 },
];

/** requirements.md 1.8 — the step indicator marks its current step with `aria-current="step"`. */
export function StepIndicator({ current }: { current: PickerStep }) {
  return (
    <div className="flex items-center gap-2 px-6 pt-3.5">
      {STEPS.map((step, index) => (
        <div key={step.key} className="flex items-center gap-2">
          <div
            {...(step.key === current ? { "aria-current": "step" as const } : {})}
            className="flex items-center gap-1.5"
          >
            <span
              className={cn(
                "flex h-5 w-5 items-center justify-center rounded-full text-[11px] font-bold",
                step.key === current
                  ? "bg-primary text-primary-foreground"
                  : "bg-muted text-muted-foreground",
              )}
            >
              {step.ordinal}
            </span>
            <span
              className={cn(
                "text-sm",
                step.key === current ? "font-semibold" : "font-medium text-muted-foreground",
              )}
            >
              {step.label}
            </span>
          </div>
          {index < STEPS.length - 1 && <div className="h-px w-6 bg-border" />}
        </div>
      ))}
    </div>
  );
}
