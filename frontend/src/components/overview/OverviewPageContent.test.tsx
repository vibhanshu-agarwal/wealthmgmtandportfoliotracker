import { render, screen } from "@testing-library/react";
import { vi, describe, it, expect, beforeEach } from "vitest";
import { OverviewPageContent } from "./OverviewPageContent";

// ── Mocks ─────────────────────────────────────────────────────────────────────

const mockReplace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: mockReplace }),
}));

const mockUseSession = vi.fn();
vi.mock("@/lib/auth-client", () => ({
  useSession: () => mockUseSession(),
}));

// Mock child components to isolate OverviewPageContent logic
vi.mock("@/components/portfolio/SummaryCards", () => ({
  SummaryCards: () => <div data-testid="mock-summary-cards">SummaryCards</div>,
}));

vi.mock("@/components/charts/PerformanceChart", () => ({
  PerformanceChart: () => (
    <div data-testid="mock-performance-chart">PerformanceChart</div>
  ),
}));

vi.mock("@/components/charts/AllocationChart", () => ({
  AllocationChart: () => (
    <div data-testid="mock-allocation-chart">AllocationChart</div>
  ),
}));

vi.mock("@/components/portfolio/HoldingsTable", () => ({
  HoldingsTable: () => (
    <div data-testid="mock-holdings-table">HoldingsTable</div>
  ),
}));

// ── Helpers ───────────────────────────────────────────────────────────────────

const authenticatedSession = {
  data: { user: { id: "u1", name: "Test User", email: "test@example.com" } },
  isPending: false,
};

const pendingSession = { data: null, isPending: true };
const unauthenticatedSession = { data: null, isPending: false };

// ── Tests ─────────────────────────────────────────────────────────────────────

describe("OverviewPageContent", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders skeleton when session is pending", () => {
    mockUseSession.mockReturnValue(pendingSession);
    const { container } = render(<OverviewPageContent />);
    // Skeleton has animate-pulse class
    expect(container.querySelector(".animate-pulse")).toBeInTheDocument();
  });

  it("redirects to /login when unauthenticated", () => {
    mockUseSession.mockReturnValue(unauthenticatedSession);
    render(<OverviewPageContent />);
    expect(mockReplace).toHaveBeenCalledWith("/login");
  });

  it("renders null after redirect when unauthenticated", () => {
    mockUseSession.mockReturnValue(unauthenticatedSession);
    const { container } = render(<OverviewPageContent />);
    expect(container.innerHTML).toBe("");
  });

  it("renders SummaryCards when authenticated", () => {
    mockUseSession.mockReturnValue(authenticatedSession);
    render(<OverviewPageContent />);
    expect(screen.getByTestId("mock-summary-cards")).toBeInTheDocument();
  });

  it("renders PerformanceChart when authenticated", () => {
    mockUseSession.mockReturnValue(authenticatedSession);
    render(<OverviewPageContent />);
    expect(screen.getByTestId("mock-performance-chart")).toBeInTheDocument();
  });

  it("renders AllocationChart when authenticated", () => {
    mockUseSession.mockReturnValue(authenticatedSession);
    render(<OverviewPageContent />);
    expect(screen.getByTestId("mock-allocation-chart")).toBeInTheDocument();
  });

  it("does NOT render HoldingsTable", () => {
    mockUseSession.mockReturnValue(authenticatedSession);
    render(<OverviewPageContent />);
    expect(screen.queryByTestId("mock-holdings-table")).not.toBeInTheDocument();
  });

  it('renders "View Portfolio →" link with href="/portfolio"', () => {
    mockUseSession.mockReturnValue(authenticatedSession);
    render(<OverviewPageContent />);
    const link = screen.getByText("View Portfolio →");
    expect(link).toBeInTheDocument();
    expect(link.closest("a")).toHaveAttribute("href", "/portfolio");
  });
});
