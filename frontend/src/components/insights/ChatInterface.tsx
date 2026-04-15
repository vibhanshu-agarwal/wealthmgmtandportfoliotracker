"use client";

import {
  useActionState,
  useState,
  useRef,
  useEffect,
  useCallback,
  type FormEvent,
} from "react";
import { Send } from "lucide-react";
import {
  sendChatMessage,
  type ChatActionState,
} from "@/lib/api/insights-actions";
import { ChatBubble } from "./ChatBubble";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import type { ChatMessage } from "@/types/insights";

const initialState: ChatActionState = {
  response: null,
  error: null,
  status: null,
};

/**
 * Client component for the conversational chat panel.
 * Uses a Server Action (sendChatMessage) invoked via useActionState
 * to keep the POST /api/chat call on the server.
 */
export function ChatInterface() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [draftMessage, setDraftMessage] = useState("");
  const scrollRef = useRef<HTMLDivElement>(null);
  const [actionState, formAction, isPending] = useActionState(
    sendChatMessage,
    initialState,
  );

  // Auto-scroll to latest message
  useEffect(() => {
    scrollRef.current?.scrollIntoView?.({ behavior: "smooth" });
  }, [messages, isPending]);

  // Append assistant/error bubble once the Server Action completes.
  useEffect(() => {
    if (actionState.status === null) {
      return;
    }

    if (actionState.response) {
      setMessages((prev) => [
        ...prev,
        {
          id: crypto.randomUUID(),
          role: "assistant",
          content: actionState.response,
          timestamp: new Date(),
        },
      ]);
      setDraftMessage("");
      return;
    }

    if (actionState.error) {
      setMessages((prev) => [
        ...prev,
        {
          id: crypto.randomUUID(),
          role: "assistant",
          content: actionState.error,
          timestamp: new Date(),
        },
      ]);
    }
  }, [actionState]);

  // Handle optimistic UI update before the Server Action resolves.
  const handleSubmit = useCallback((event: FormEvent<HTMLFormElement>) => {
    const message = draftMessage.trim();
    if (!message) {
      event.preventDefault();
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
  }, [draftMessage]);

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
          action={formAction}
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
