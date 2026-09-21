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

  it.each(["WealthTracker", "Portfolio Management", "Navigation", "Account"])(
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

// The footer used to render "Phase 1 · v0.1.0". The product version lives in root VERSION and
// nothing in the running frontend reads it, so any version literal here can only go stale.
// Asserting the absence of "v0.1.0" alone would let a bump to "v0.9.0" pass and reintroduce the
// same defect one release later, so both checks are pattern-based.
describe("Sidebar — no stale release metadata", () => {
  // A phase label ("Phase 1", "Phase-1", "Phase I") or a version: any three-part x.y.z digit
  // run, or a v/Version-prefixed two-part one ("v0.9", "Version 0.9"). A bare two-part decimal
  // is deliberately not matched -- it would trip on any future number in the sidebar.
  // Unanchored: a leading `\b` fails whenever the match follows another word character, which
  // is what sank the first draft of this test ("SettingsPhase 1" has no boundary before "P").
  // The known cost: a future label ending in "v" or "version" that abuts a decimal ("NAV 1.02",
  // "Div 3.5%", "Rev 1.2") will match the prefixed pattern. Accepted on purpose -- restoring `\b`
  // or a lookbehind would reopen a JSX-split `v{0}.{9}` rendered after a label ("Settingsv0.9"),
  // the likelier regression. If a legitimate label trips it, narrow the pattern for that label;
  // do not re-anchor it.
  const RELEASE_PATTERNS: ReadonlyArray<readonly [string, RegExp]> = [
    ["phase label", /phase\s*[-–—:·]?\s*(?:\d|[ivx]+\b)/i],
    ["x.y.z version", /\d+\.\d+\.\d+/],
    ["prefixed version", /v(?:ersion)?\s*\d+\.\d+/i],
  ];
  // Text a person sees or hears. Deliberately not `class`, `data-*` or SVG path data, which
  // legitimately contain digit runs that are not versions.
  const HUMAN_FACING_ATTRIBUTES = ["aria-label", "aria-description", "title", "alt", "placeholder"];

  function expectNoReleaseMetadata(value: string, where: string) {
    for (const [kind, pattern] of RELEASE_PATTERNS) {
      expect(value, `${kind} in ${where}`).not.toMatch(pattern);
    }
  }

  function renderedTextNodes(root: Element): string[] {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const texts: string[] = [];
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const value = node.textContent?.trim();
      if (value) texts.push(value);
    }
    return texts;
  }

  // Both views, because each catches what the other cannot. The concatenation is the one that
  // matters: JSX such as `<p>Phase {phase}</p>` renders "Phase 1" as two adjacent text nodes
  // ("Phase ", "1"), so no single node holds the whole string -- an earlier version of this
  // test checked nodes only and passed with exactly that footer present. Concatenation can
  // only add matches (the patterns are unanchored) and today's sidebar labels contain no
  // digits. The per-node view keeps boundary-sensitive matches such as "Phase I" reliable when
  // the concatenation would run them into the next label.
  it("renders no phase label or version string, even when JSX splits it across text nodes", () => {
    const aside = renderSidebar();
    const texts = renderedTextNodes(aside);
    const rendered = (aside.textContent ?? "").replace(/\s+/g, " ");

    // Guards a vacuous pass: both views must actually contain the sidebar's own labels.
    expect(texts).toEqual(expect.arrayContaining(["WealthTracker", "Settings"]));
    expect(rendered).toContain("WealthTracker");
    expect(rendered).toContain("Settings");

    expectNoReleaseMetadata(rendered, "rendered text");
    for (const text of texts) expectNoReleaseMetadata(text, `text node "${text}"`);
  });

  it("exposes no phase label or version string through an accessible attribute", () => {
    const aside = renderSidebar();
    const elements = [aside, ...aside.querySelectorAll("*")];
    let visited = 0;

    for (const element of elements) {
      visited += 1;
      for (const name of HUMAN_FACING_ATTRIBUTES) {
        const value = element.getAttribute(name);
        if (value !== null) {
          expectNoReleaseMetadata(value, `${name} on <${element.tagName.toLowerCase()}>`);
        }
      }
    }
    // Guards a vacuous pass: every element was walked, not only the root. Counting attributes
    // found instead would be satisfied by the aside's own aria-label alone.
    expect(visited).toBe(aside.querySelectorAll("*").length + 1);
    expect(visited).toBeGreaterThan(NAV.length);
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
