"use client";

import {
  useState,
  useRef,
  useEffect,
  useCallback,
  type FormEvent,
} from "react";
import { Send } from "lucide-react";
import { ChatBubble } from "./ChatBubble";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import type { ChatMessage } from "@/types/insights";
import { useAuthenticatedUserId } from "@/lib/hooks/useAuthenticatedUserId";
import { postChatMessage } from "@/lib/api/insights";

/**
 * Client component for the conversational chat panel.
 * Uses direct authenticated client fetch to POST /api/chat.
 */
export function ChatInterface() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [draftMessage, setDraftMessage] = useState("");
  const [isPending, setIsPending] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const { token, status } = useAuthenticatedUserId();

  // Auto-scroll to latest message
  useEffect(() => {
    scrollRef.current?.scrollIntoView?.({ behavior: "smooth" });
  }, [messages, isPending]);

  const appendAssistantMessage = useCallback((content: string) => {
    setMessages((prev) => [
      ...prev,
      {
        id: crypto.randomUUID(),
        role: "assistant",
        content,
        timestamp: new Date(),
      },
    ]);
  }, []);

  // Handle optimistic UI update and call insight-service chat.
  const handleSubmit = useCallback(async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const message = draftMessage.trim();
    if (!message) {
      return;
    }

    setMessages((prev) => [
      ...prev,
      {
        id: crypto.randomUUID(),
        role: "user",
        content: message,
        timestamp: new Date(),
      },
    ]);
    setDraftMessage("");

    if (status !== "authenticated" || !token) {
      appendAssistantMessage("Your session is unavailable. Please log in again.");
      return;
    }

    setIsPending(true);
    try {
      const result = await postChatMessage({ message }, token);
      appendAssistantMessage(result.response);
    } catch (err) {
      const statusMatch = (err as Error).message.match(/\((\d+)\)/);
      const requestStatus = statusMatch ? parseInt(statusMatch[1], 10) : 500;
      if (requestStatus === 503) {
        appendAssistantMessage(
          "AI service is temporarily unavailable. Please try again later.",
        );
      } else {
        appendAssistantMessage("Something went wrong. Please try again.");
      }
    } finally {
      setIsPending(false);
    }
  }, [appendAssistantMessage, draftMessage, status, token]);

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base">AI Chat</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {/* Message list */}
        <div
          className="flex flex-col gap-3 overflow-y-auto max-h-96 min-h-[12rem] p-1"
          data-testid="chat-messages"
        >
          {messages.length === 0 && !isPending && (
            <p className="text-sm text-muted-foreground text-center py-8">
              Ask a question about any tracked ticker.
            </p>
          )}

          {messages.map((msg) => (
            <ChatBubble key={msg.id} message={msg} />
          ))}

          {/* Typing indicator while pending */}
          {isPending && (
            <div
              className="flex justify-start"
              data-testid="chat-typing-indicator"
            >
              <div className="bg-muted rounded-2xl rounded-bl-md px-4 py-2.5">
                <span className="flex gap-1">
                  <span className="h-2 w-2 rounded-full bg-muted-foreground/40 animate-bounce [animation-delay:0ms]" />
                  <span className="h-2 w-2 rounded-full bg-muted-foreground/40 animate-bounce [animation-delay:150ms]" />
                  <span className="h-2 w-2 rounded-full bg-muted-foreground/40 animate-bounce [animation-delay:300ms]" />
                </span>
              </div>
            </div>
          )}

          <div ref={scrollRef} />
        </div>

        {/* Input form */}
        <form
          onSubmit={handleSubmit}
          className="flex gap-2"
          data-testid="chat-form"
        >
          <Input
            name="message"
            placeholder="Ask about a ticker..."
            value={draftMessage}
            onChange={(event) => setDraftMessage(event.target.value)}
            disabled={isPending}
            autoComplete="off"
            data-testid="chat-input"
          />
          <Button
            type="submit"
            size="icon"
            disabled={isPending}
            data-testid="chat-send"
          >
            <Send className="h-4 w-4" />
            <span className="sr-only">Send</span>
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
