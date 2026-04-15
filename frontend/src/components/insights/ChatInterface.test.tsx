import {
  render,
  screen,
  fireEvent,
  waitFor,
  act,
} from "@testing-library/react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const mockPostChatMessage = vi.fn();

vi.mock("@/lib/api/insights", () => ({
  postChatMessage: (request: { message: string }, token: string) =>
    mockPostChatMessage(request, token),
}));
vi.mock("@/lib/hooks/useAuthenticatedUserId", () => ({
  useAuthenticatedUserId: () => ({
    userId: "user-001",
    token: "test-jwt",
    status: "authenticated" as const,
    error: null,
  }),
}));

// crypto.randomUUID is not available in jsdom
let uuidCounter = 0;
vi.stubGlobal("crypto", {
  ...globalThis.crypto,
  randomUUID: () => `test-uuid-${++uuidCounter}`,
});

const { ChatInterface } = await import("./ChatInterface");

function renderWithQueryClient() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <ChatInterface />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  uuidCounter = 0;
  mockPostChatMessage.mockReset();
  mockPostChatMessage.mockResolvedValue({
    response: "AAPL is trading at $178.50 with a bullish trend.",
  });
});

// ── Property 4: Chat submission lifecycle ─────────────────────────────────────

describe("ChatInterface — Submission lifecycle", () => {
  it("appends a user bubble when a message is submitted", async () => {
    renderWithQueryClient();

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
    renderWithQueryClient();

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
    renderWithQueryClient();

    expect(
      screen.getByText("Ask a question about any tracked ticker."),
    ).toBeInTheDocument();
  });
});

// ── Loading indicator test (Requirement 6.3, 9.6) ────────────────────────────

describe("ChatInterface — Loading indicator", () => {
  it("shows typing indicator while the action is pending", async () => {
    // Use a delayed request so we can observe pending UI.
    let resolveRequest!: (value: { response: string }) => void;
    mockPostChatMessage.mockImplementation(
      () =>
        new Promise<{ response: string }>((resolve) => {
          resolveRequest = resolve;
        }),
    );

    renderWithQueryClient();

    const input = screen.getByTestId("chat-input");
    const sendBtn = screen.getByTestId("chat-send");

    await act(async () => {
      fireEvent.change(input, { target: { value: "Tell me about AAPL" } });
      fireEvent.click(sendBtn);
    });

    await waitFor(() => {
      expect(screen.getByTestId("chat-typing-indicator")).toBeInTheDocument();
    });

    await act(async () => {
      resolveRequest({ response: "AAPL is trending up." });
    });

    await waitFor(() => {
      expect(
        screen.queryByTestId("chat-typing-indicator"),
      ).not.toBeInTheDocument();
    });
  });
});

describe("ChatInterface — Error handling", () => {
  it("displays 503-specific error message", async () => {
    mockPostChatMessage.mockRejectedValue(
      new Error("Request failed (503) for /api/chat"),
    );

    renderWithQueryClient();

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
    mockPostChatMessage.mockRejectedValue(
      new Error("Request failed (500) for /api/chat"),
    );

    renderWithQueryClient();

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
