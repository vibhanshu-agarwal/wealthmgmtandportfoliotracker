/**
 * Phase 2.1 — dashboard shell containment contract.
 *
 * The outer `h-screen` wrapper is the shell's clipping boundary and `<main>` is the page's one
 * scroller. Two properties are required of that wrapper, and both are needed:
 *
 * - `relative`. An absolutely positioned descendant with no positioned ancestor — every `sr-only`
 *   span, e.g. the chat Send button's label — is contained by the initial containing block, so it
 *   escapes the wrapper's clipping and grows the *document's* scroll area.
 * - `overflow-clip`, not `overflow-hidden`. `hidden` still makes the wrapper a scroll container that
 *   `scrollIntoView()` can scroll (the chat's auto-scroll; Next's router on navigation), so once the
 *   wrapper contains those spans it scrolls the whole shell, header included, out of view. `clip`
 *   clips identically but is not a scroll container, so nothing can move the shell.
 *
 * jsdom applies no Tailwind CSS, so this pins the class contract; the rendered behaviour is
 * verified in a real browser.
 */

import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { DashboardLayout } from "./DashboardLayout";

// DashboardLayout owns only the shell structure; Sidebar and Header have their own tests and
// pull in auth, query and theme providers that are irrelevant here.
vi.mock("./Sidebar", () => ({ Sidebar: () => <aside data-testid="sidebar" /> }));
vi.mock("./Header", () => ({ Header: () => <header data-testid="header" /> }));

const POSITIONED = /^(relative|absolute|fixed|sticky)$/;
const CLIPS_VERTICALLY = /^overflow-(hidden|auto|scroll|clip|y-(hidden|auto|scroll|clip))$/;
/** Every overflow value that makes a box a scroll container (`visible` and `clip` do not). */
const SCROLL_CONTAINER_OVERFLOW = /^overflow(-[xy])?-(hidden|auto|scroll)$/;

const hasClass = (element: Element, pattern: RegExp) =>
  Array.from(element.classList).some((token) => pattern.test(token));

function nearestPositionedAncestor(element: Element): Element | null {
  for (let node = element.parentElement; node; node = node.parentElement) {
    if (hasClass(node, POSITIONED)) return node;
  }
  return null;
}

function renderShell() {
  const { container } = render(
    <DashboardLayout>
      <div>
        <span className="sr-only">Send</span>
      </div>
    </DashboardLayout>,
  );
  return {
    label: screen.getByText("Send"),
    wrapper: container.querySelector(".h-screen") as HTMLElement,
    main: container.querySelector("main") as HTMLElement,
  };
}

describe("DashboardLayout — shell containment", () => {
  it("contains absolutely positioned page content in a clipping ancestor, not the page", () => {
    const { label } = renderShell();

    const containingBlock = nearestPositionedAncestor(label);

    expect(containingBlock, "no positioned ancestor: the label's containing block is the page").not.toBeNull();
    expect(hasClass(containingBlock!, CLIPS_VERTICALLY)).toBe(true);
  });

  it("makes the one-viewport-tall wrapper that containing block, clipped with overflow-clip", () => {
    const { wrapper, label } = renderShell();

    expect(wrapper).toHaveClass("relative", "h-screen", "overflow-clip");
    expect(nearestPositionedAncestor(label)).toBe(wrapper);
  });

  it("does not make the wrapper a scroll container, so scrollIntoView() cannot move the shell", () => {
    const { wrapper } = renderShell();

    const scrollContainerClasses = Array.from(wrapper.classList).filter((token) =>
      SCROLL_CONTAINER_OVERFLOW.test(token),
    );

    expect(scrollContainerClasses).toEqual([]);
  });

  it("keeps <main> as the page's own scroll region inside the wrapper", () => {
    const { wrapper, main } = renderShell();

    expect(main).toHaveAttribute("id", "main-content");
    expect(main).toHaveClass("overflow-y-auto");
    expect(wrapper).toContainElement(main);
  });
});
