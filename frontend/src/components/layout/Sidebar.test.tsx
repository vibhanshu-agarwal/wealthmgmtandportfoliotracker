/**
 * Phase 2.1 — responsive dashboard shell.
 *
 * Below the `md` breakpoint the sidebar collapses to a 64px icon rail; at `md`+ the
 * full 240px labelled sidebar is unchanged. Navigation must stay available at every
 * width — merely hiding the sidebar below `md` would remove all five links.
 *
 * jsdom applies no Tailwind CSS, so this suite pins the class contract that Tailwind
 * turns into layout. The rendered layout itself is verified in a real browser.
 */

import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeAll, describe, expect, it, vi } from "vitest";
import { TooltipProvider } from "@/components/ui/tooltip";
import { Sidebar } from "./Sidebar";

vi.mock("next/navigation", () => ({ usePathname: () => "/portfolio" }));

// Radix Tooltip positions its content with a ResizeObserver, which jsdom doesn't provide.
beforeAll(() => {
  global.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
});

const NAV = [
  { label: "Overview", href: "/overview" },
  { label: "Portfolio", href: "/portfolio" },
  { label: "Market Data", href: "/market-data" },
  { label: "AI Insights", href: "/ai-insights" },
  { label: "Settings", href: "/settings" },
] as const;

function renderSidebar() {
  render(
    <TooltipProvider>
      <Sidebar />
    </TooltipProvider>,
  );
  return screen.getByRole("complementary", { name: "Main navigation" });
}

describe("Sidebar — navigation availability", () => {
  it("renders all five links, each with an accessible name and destination", () => {
    const aside = renderSidebar();

    expect(within(aside).getAllByRole("link")).toHaveLength(NAV.length);
    for (const { label, href } of NAV) {
      expect(within(aside).getByRole("link", { name: label })).toHaveAttribute("href", href);
    }
  });

  // Class-based guard: it catches the usual ways of hiding a subtree, not every one (e.g. an
  // arbitrary `w-0`). The browser pass is the real check that links stay on screen.
  it("never hides a link, or any ancestor of a link, at any width", () => {
    const aside = renderSidebar();
    const hiding = [".hidden", ".invisible", ".sr-only", '[class~="md:hidden"]', '[class~="max-md:hidden"]'].join(", ");

    for (const link of within(aside).getAllByRole("link")) {
      expect(link.closest(hiding)).toBeNull();
    }
  });
});

describe("Sidebar — icon rail below md", () => {
  it("is a 64px rail below md and the 240px sidebar at md+", () => {
    const aside = renderSidebar();

    expect(aside).toHaveClass("w-16", "md:w-60", "shrink-0");
    expect(aside).not.toHaveClass("w-60");
  });

  it("centres each icon below md and left-aligns it at md+", () => {
    const aside = renderSidebar();

    for (const link of within(aside).getAllByRole("link")) {
      expect(link).toHaveClass("justify-center", "md:justify-start");
    }
  });

  // With the label out of flow the link loses its line box; without a floor the rail's
  // touch targets would be shorter than the desktop rows. 44px is the usual minimum.
  it("keeps a 44px-tall touch target below md without changing the natural md+ row height", () => {
    const aside = renderSidebar();

    for (const link of within(aside).getAllByRole("link")) {
      expect(link).toHaveClass("min-h-11", "md:min-h-0");
    }
  });

  it("keeps every label as screen-reader text below md and shows it at md+", () => {
    const aside = renderSidebar();

    for (const { label } of NAV) {
      const link = within(aside).getByRole("link", { name: label });
      expect(within(link).getByText(label)).toHaveClass("sr-only", "md:not-sr-only");
    }
  });

  // An absolutely positioned sr-only label whose containing block is the page (not the
  // link) escapes the nav's overflow clipping and can extend the document's scroll area
  // on very short viewports — the same mechanism that makes the AI Insights page scroll.
  it("contains each link's screen-reader label so it cannot extend the page scroll area", () => {
    const aside = renderSidebar();

    for (const link of within(aside).getAllByRole("link")) {
      expect(link).toHaveClass("relative");
    }
  });

  it.each(["WealthTracker", "Portfolio Management", "Navigation", "Account", "Phase 1 · v0.1.0"])(
    "hides %s below md and restores it at md+",
    (text) => {
      renderSidebar();

      expect(screen.getByText(text).closest(".hidden.md\\:block")).not.toBeNull();
    },
  );

  it("hides the active-page dot below md and restores it at md+", () => {
    const aside = renderSidebar();

    const activeLink = within(aside).getByRole("link", { name: "Portfolio" });
    expect(activeLink).toHaveAttribute("aria-current", "page");
    expect(activeLink.querySelector(".rounded-full")).toHaveClass("hidden", "md:block");
  });
});

describe("Sidebar — narrow-screen tooltip", () => {
  it("shows the label tooltip below md only, replacing the old always-hidden tooltip", async () => {
    const aside = renderSidebar();

    fireEvent.focus(within(aside).getByRole("link", { name: "Portfolio" }));

    // Radix nests its role="tooltip" copy inside the styled content node.
    const tooltip = await screen.findByRole("tooltip");
    expect(tooltip).toHaveTextContent("Portfolio");
    const content = tooltip.parentElement;
    expect(content).toHaveClass("md:hidden");
    expect(content).not.toHaveClass("hidden");
  });
});
