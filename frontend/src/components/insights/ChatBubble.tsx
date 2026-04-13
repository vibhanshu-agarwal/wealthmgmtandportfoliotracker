import { cn } from "@/lib/utils/cn";
import type { ChatMessage } from "@/types/insights";

/**
 * Formats a Date as a relative timestamp (e.g. "just now", "2 minutes ago").
 */
function formatRelativeTime(date: Date): string {
  const seconds = Math.floor((Date.now() - date.getTime()) / 1000);
  if (seconds < 60) return "just now";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

interface ChatBubbleProps {
  message: ChatMessage;
}

/**
 * Presentational component for a single chat message.
 * User messages are right-aligned with primary background;
 * assistant messages are left-aligned with muted background.
 */
export function ChatBubble({ message }: ChatBubbleProps) {
  const isUser = message.role === "user";

  return (
    <div
      className={cn("flex w-full", isUser ? "justify-end" : "justify-start")}
    >
      <div
        className={cn(
          "max-w-[80%] rounded-2xl px-4 py-2.5",
          isUser
            ? "bg-primary text-primary-foreground rounded-br-md"
            : "bg-muted text-foreground rounded-bl-md",
        )}
      >
        <p className="text-sm whitespace-pre-wrap">{message.content}</p>
        <p
          className={cn(
            "mt-1 text-[10px]",
            isUser
              ? "text-primary-foreground/60 text-right"
              : "text-muted-foreground",
          )}
        >
          {formatRelativeTime(message.timestamp)}
        </p>
      </div>
    </div>
  );
}
