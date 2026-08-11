import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import SignupPage from "./page";

const pushMock = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

describe("SignupPage", () => {
  const mockFetch = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", mockFetch);
    vi.stubGlobal("localStorage", {
      getItem: vi.fn(),
      setItem: vi.fn(),
      removeItem: vi.fn(),
    });
    pushMock.mockClear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  function fillForm({ name, email, password }: { name: string; email: string; password: string }) {
    fireEvent.change(screen.getByLabelText(/name/i), { target: { value: name } });
    fireEvent.change(screen.getByLabelText(/email/i), { target: { value: email } });
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: password } });
  }

  it("shows a field-specific error and does not call the endpoint for a short password", async () => {
    render(<SignupPage />);
    fillForm({ name: "Jane Doe", email: "jane@example.com", password: "short" });
    fireEvent.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByText(/at least 12 characters/i)).toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
    expect(screen.getByLabelText(/email/i)).toHaveValue("jane@example.com");
  });

  it("persists the session and navigates to /overview on 201", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 201,
      json: async () => ({ token: "t", userId: "u1", email: "jane@example.com", name: "Jane Doe" }),
    });

    render(<SignupPage />);
    fillForm({ name: "Jane Doe", email: "jane@example.com", password: "a-strong-password-123" });
    fireEvent.click(screen.getByRole("button", { name: /create account/i }));

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/overview"));
  });

  it("shows the duplicate-email message and retains email/name on 409", async () => {
    mockFetch.mockResolvedValueOnce({ ok: false, status: 409 });

    render(<SignupPage />);
    fillForm({ name: "Jane Doe", email: "jane@example.com", password: "a-strong-password-123" });
    fireEvent.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByText(/already exists/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/email/i)).toHaveValue("jane@example.com");
    expect(screen.getByLabelText(/name/i)).toHaveValue("Jane Doe");
  });

  it("has a link back to /login", () => {
    render(<SignupPage />);
    expect(screen.getByRole("link", { name: /sign in/i })).toHaveAttribute("href", "/login");
  });
});
