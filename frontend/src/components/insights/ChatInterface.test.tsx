import {
  render,
  screen,
  fireEvent,
  waitFor,
  act,
} from "@testing-library/react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { AuthenticatedUser } from "@/lib/hooks/useAuthenticatedUserId";

const mockPostChatMessage = vi.fn();
const mockAuthState: AuthenticatedUser = {
  userId: "user-001",
  token: "test-jwt",
  status: "authenticated",
  error: null,
};

vi.mock("@/lib/api/insights", () => ({
  postChatMessage: (request: { message: string }, token: string) =>
    mockPostChatMessage(request, token),
}));
vi.mock("@/lib/hooks/useAuthenticatedUserId", () => ({
  useAuthenticatedUserId: () => mockAuthState,
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
  mockAuthState.userId = "user-001";
  mockAuthState.token = "test-jwt";
  mockAuthState.status = "authenticated";
  mockAuthState.error = null;
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

describe("ChatInterface — Rate limit handling (429)", () => {
  it("shows a distinguishable rate-limit message (not the generic error copy) on 429", async () => {
    const { RateLimitError } = await import("@/lib/api/fetchWithAuth");
    mockPostChatMessage.mockRejectedValue(
      new RateLimitError("Rate limit exceeded (429) for /api/chat", 6),
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
        screen.getByText("You're sending messages too quickly. Please wait 6s and try again."),
      ).toBeInTheDocument();
    });
    expect(
      screen.queryByText("Something went wrong. Please try again."),
    ).not.toBeInTheDocument();
  });

  it("disables the submit control with a visible countdown after a 429", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { RateLimitError } = await import("@/lib/api/fetchWithAuth");
    mockPostChatMessage.mockRejectedValue(
      new RateLimitError("Rate limit exceeded (429) for /api/chat", 3),
    );

    renderWithQueryClient();

    const input = screen.getByTestId("chat-input");
    const sendBtn = screen.getByTestId("chat-send");

    await act(async () => {
      fireEvent.change(input, { target: { value: "Tell me about AAPL" } });
      fireEvent.click(sendBtn);
    });

    await waitFor(() => {
      expect(screen.getByTestId("chat-rate-limit-countdown")).toBeInTheDocument();
    });
    expect(sendBtn).toBeDisabled();
    expect(input).toBeDisabled();

    await act(async () => {
      vi.advanceTimersByTime(3000);
    });

    await waitFor(() => {
      expect(screen.queryByTestId("chat-rate-limit-countdown")).not.toBeInTheDocument();
    });
    expect(sendBtn).not.toBeDisabled();

    vi.useRealTimers();
  });

  it("falls back to a default cooldown when Retry-After is unparseable/absent", async () => {
    const { RateLimitError } = await import("@/lib/api/fetchWithAuth");
    mockPostChatMessage.mockRejectedValue(
      new RateLimitError("Rate limit exceeded (429) for /api/chat", null),
    );

    renderWithQueryClient();

    const input = screen.getByTestId("chat-input");
    const sendBtn = screen.getByTestId("chat-send");

    await act(async () => {
      fireEvent.change(input, { target: { value: "Tell me about AAPL" } });
      fireEvent.click(sendBtn);
    });

    await waitFor(() => {
      expect(screen.getByTestId("chat-rate-limit-countdown")).toBeInTheDocument();
    });
  });

  it("does not automatically retry the request after a 429", async () => {
    const { RateLimitError } = await import("@/lib/api/fetchWithAuth");
    mockPostChatMessage.mockRejectedValue(
      new RateLimitError("Rate limit exceeded (429) for /api/chat", 1),
    );

    renderWithQueryClient();

    const input = screen.getByTestId("chat-input");
    const sendBtn = screen.getByTestId("chat-send");

    await act(async () => {
      fireEvent.change(input, { target: { value: "Tell me about AAPL" } });
      fireEvent.click(sendBtn);
    });

    await waitFor(() => {
      expect(screen.getByTestId("chat-rate-limit-countdown")).toBeInTheDocument();
    });

    expect(mockPostChatMessage).toHaveBeenCalledOnce();
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

  it("displays auth unavailable guidance when user token is missing", async () => {
    mockAuthState.userId = "";
    mockAuthState.token = "";
    mockAuthState.status = "error";
    mockAuthState.error = "JWT exchange failed (503)";

    renderWithQueryClient();

    const input = screen.getByTestId("chat-input");
    const sendBtn = screen.getByTestId("chat-send");

    await act(async () => {
      fireEvent.change(input, { target: { value: "Tell me about AAPL" } });
      fireEvent.click(sendBtn);
    });

    await waitFor(() => {
      expect(
        screen.getByText("Your session is unavailable. Please log in again."),
      ).toBeInTheDocument();
    });
    expect(mockPostChatMessage).not.toHaveBeenCalled();
  });
});
