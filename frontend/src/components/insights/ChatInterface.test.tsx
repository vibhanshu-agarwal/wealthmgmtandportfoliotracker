import {
  render,
  screen,
  fireEvent,
  waitFor,
  act,
} from "@testing-library/react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import type { ChatActionState } from "@/lib/api/insights-actions";

// ── Mock the Server Action ────────────────────────────────────────────────────
// We mock the entire module so useActionState receives a plain async function
// instead of a real Server Action (which requires a Next.js server runtime).

let mockActionImpl: (
  prev: ChatActionState,
  formData: FormData,
) => Promise<ChatActionState>;

vi.mock("@/lib/api/insights-actions", () => ({
  sendChatMessage: (prev: ChatActionState, formData: FormData) =>
    mockActionImpl(prev, formData),
}));

// crypto.randomUUID is not available in jsdom
let uuidCounter = 0;
vi.stubGlobal("crypto", {
  ...globalThis.crypto,
  randomUUID: () => `test-uuid-${++uuidCounter}`,
});

const { ChatInterface } = await import("./ChatInterface");

beforeEach(() => {
  uuidCounter = 0;
  // Default: successful response
  mockActionImpl = async () => ({
    response: "AAPL is trading at $178.50 with a bullish trend.",
    error: null,
    status: 200,
  });
});

// ── Property 4: Chat submission lifecycle ─────────────────────────────────────

describe("ChatInterface — Submission lifecycle", () => {
  it("appends a user bubble when a message is submitted", async () => {
    render(<ChatInterface />);

    const input = screen.getByTestId("chat-input");
    const sendBtn = screen.getByTestId("chat-send");

    await act(async () => {
      fireEvent.change(input, { target: { value: "Tell me about AAPL" } });
      fireEvent.click(sendBtn);
    });

    await waitFor(() => {
      expect(screen.getByText("Tell me about AAPL")).toBeInTheDocument();
    });
  });

  it("shows assistant response after successful submission", async () => {
    render(<ChatInterface />);

    const input = screen.getByTestId("chat-input");
    const sendBtn = screen.getByTestId("chat-send");

    await act(async () => {
      fireEvent.change(input, { target: { value: "Tell me about AAPL" } });
      fireEvent.click(sendBtn);
    });

    await waitFor(() => {
      expect(
        screen.getByText("AAPL is trading at $178.50 with a bullish trend."),
      ).toBeInTheDocument();
    });
  });

  it("renders empty state prompt when no messages exist", () => {
    render(<ChatInterface />);

    expect(
      screen.getByText("Ask a question about any tracked ticker."),
    ).toBeInTheDocument();
  });
});

// ── Loading indicator test (Requirement 6.3, 9.6) ────────────────────────────

describe("ChatInterface — Loading indicator", () => {
  it("shows typing indicator while the action is pending", async () => {
    // Use a delayed action so we can observe the pending state
    let resolveAction!: (value: ChatActionState) => void;
    mockActionImpl = () =>
      new Promise<ChatActionState>((resolve) => {
        resolveAction = resolve;
      });

    render(<ChatInterface />);

    const input = screen.getByTestId("chat-input");
    const sendBtn = screen.getByTestId("chat-send");

    await act(async () => {
      fireEvent.change(input, { target: { value: "Tell me about AAPL" } });
      fireEvent.click(sendBtn);
    });

    // While pending, typing indicator should be visible
    await waitFor(() => {
      expect(screen.getByTestId("chat-typing-indicator")).toBeInTheDocument();
    });

    // Resolve the action
    await act(async () => {
      resolveAction({
        response: "AAPL is trending up.",
        error: null,
        status: 200,
      });
    });

    // After resolution, typing indicator should be gone
    await waitFor(() => {
      expect(
        screen.queryByTestId("chat-typing-indicator"),
      ).not.toBeInTheDocument();
    });
  });
});

// ── Property 5: Chat error handling — 503 vs generic ─────────────────────────

describe("ChatInterface — Error handling", () => {
  it("displays 503-specific error message", async () => {
    mockActionImpl = async () => ({
      response: null,
      error: "AI service is temporarily unavailable. Please try again later.",
      status: 503,
    });

    render(<ChatInterface />);

    const input = screen.getByTestId("chat-input");
    const sendBtn = screen.getByTestId("chat-send");

    await act(async () => {
      fireEvent.change(input, { target: { value: "Tell me about AAPL" } });
      fireEvent.click(sendBtn);
    });

    await waitFor(() => {
      expect(
        screen.getByText(
          "AI service is temporarily unavailable. Please try again later.",
        ),
      ).toBeInTheDocument();
    });
  });

  it("displays generic error message for non-503 failures", async () => {
    mockActionImpl = async () => ({
      response: null,
      error: "Something went wrong. Please try again.",
      status: 500,
    });

    render(<ChatInterface />);

    const input = screen.getByTestId("chat-input");
    const sendBtn = screen.getByTestId("chat-send");

    await act(async () => {
      fireEvent.change(input, { target: { value: "Tell me about AAPL" } });
      fireEvent.click(sendBtn);
    });

    await waitFor(() => {
      expect(
        screen.getByText("Something went wrong. Please try again."),
      ).toBeInTheDocument();
    });
  });
});
