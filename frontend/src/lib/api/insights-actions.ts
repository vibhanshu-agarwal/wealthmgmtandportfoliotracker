"use server";

import { fetchWithAuth } from "@/lib/api/fetchWithAuth.server";
import type { ChatRequest, ChatResponse } from "@/types/insights";

/**
 * State shape returned by the sendChatMessage Server Action.
 * Consumed by useActionState in ChatInterface.
 */
export type ChatActionState = {
  response: string | null;
  error: string | null;
  status: number | null;
};

/**
 * Server Action for submitting a chat message to the insight-service.
 *
 * Follows the useActionState contract: receives the previous state and
 * FormData, returns the next state. The HTTP call runs server-side so
 * no client-side token management is needed for chat.
 *
 * @param _prevState previous ChatActionState (managed by useActionState)
 * @param formData   form submission data containing "message" and optional "ticker"
 */
export async function sendChatMessage(
  _prevState: ChatActionState,
  formData: FormData,
): Promise<ChatActionState> {
  const message = formData.get("message") as string;
  const ticker = (formData.get("ticker") as string) || undefined;

  if (!message?.trim()) {
    return { response: null, error: "Message cannot be empty.", status: 400 };
  }

  const body: ChatRequest = { message, ticker };

  try {
    const result = await fetchWithAuth<ChatResponse>("/api/chat", {
      method: "POST",
      body: JSON.stringify(body),
    });
    return { response: result.response, error: null, status: 200 };
  } catch (err) {
    const statusMatch = (err as Error).message.match(/\((\d+)\)/);
    const status = statusMatch ? parseInt(statusMatch[1], 10) : 500;

    if (status === 503) {
      return {
        response: null,
        error:
          "AI service is temporarily unavailable. Please try again later.",
        status: 503,
      };
    }

    return {
      response: null,
      error: "Something went wrong. Please try again.",
      status,
    };
  }
}
