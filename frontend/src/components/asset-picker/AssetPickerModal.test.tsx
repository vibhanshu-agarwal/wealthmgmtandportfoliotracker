/**
 * B2 Task 1.3 — `AssetPickerModal` shell: WAI-ARIA Dialog pattern (role, aria-modal,
 * aria-labelledby, focus trap, focus return, Escape discard).
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useRef, useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { AssetPickerModal } from "./AssetPickerModal";

function Harness({ onClose }: { onClose: () => void }) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  return (
    <>
      <button ref={triggerRef} data-testid="trigger" onClick={() => setOpen(true)}>
        Edit Holdings
      </button>
      <AssetPickerModal
        open={open}
        onClose={() => {
          setOpen(false);
          onClose();
        }}
        triggerRef={triggerRef}
      >
        <button>Inside dialog</button>
      </AssetPickerModal>
    </>
  );
}

describe("AssetPickerModal", () => {
  it("renders nothing when closed", () => {
    render(
      <AssetPickerModal open={false} onClose={vi.fn()}>
        <div>content</div>
      </AssetPickerModal>,
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("implements the WAI-ARIA modal dialog pattern, labelled 'Edit Holdings'", () => {
    render(
      <AssetPickerModal open onClose={vi.fn()}>
        <div>content</div>
      </AssetPickerModal>,
    );

    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveAttribute("aria-modal", "true");

    const labelledBy = dialog.getAttribute("aria-labelledby");
    expect(labelledBy).toBeTruthy();
    expect(document.getElementById(labelledBy!)).toHaveTextContent("Edit Holdings");
  });

  it("moves focus into the dialog on open", async () => {
    render(
      <AssetPickerModal open onClose={vi.fn()}>
        <button>First focusable</button>
      </AssetPickerModal>,
    );

    await waitFor(() => {
      expect(screen.getByRole("dialog")).toContainElement(document.activeElement as HTMLElement);
    });
  });

  it("closes on Escape with discard semantics (onClose fires)", async () => {
    const onClose = vi.fn();
    render(
      <AssetPickerModal open onClose={onClose}>
        <div>content</div>
      </AssetPickerModal>,
    );

    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape", code: "Escape" });

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
  });

  it("closes via the built-in close button", async () => {
    const onClose = vi.fn();
    render(
      <AssetPickerModal open onClose={onClose}>
        <div>content</div>
      </AssetPickerModal>,
    );

    fireEvent.click(screen.getByRole("button", { name: /close/i }));

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
  });

  it("returns focus to the triggering button on close", async () => {
    const onClose = vi.fn();
    render(<Harness onClose={onClose} />);

    const trigger = screen.getByTestId("trigger");
    // Simulates a real click, which focuses the button before the dialog mounts —
    // Radix's onCloseAutoFocus restores focus to whatever was focused at open time.
    trigger.focus();
    fireEvent.click(trigger);

    await waitFor(() => expect(screen.getByRole("dialog")).toBeInTheDocument());
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape", code: "Escape" });

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    await waitFor(() => expect(document.activeElement).toBe(trigger));
  });
});
