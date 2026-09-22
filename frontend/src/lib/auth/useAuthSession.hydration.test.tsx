import { act, useEffect } from "react";
import { hydrateRoot, type Root } from "react-dom/client";
import { renderToString } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { clearAuthSession, saveAuthSession, useAuthSession } from "./session";

// The dashboard is a static export: its HTML is prerendered once, with no browser and
// therefore no stored session. A signed-in hard load then hydrates that HTML in a
// browser whose localStorage already holds the session. These tests reproduce that
// exact sequence with the real hook and real React hydration.

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

interface Observation {
  isPending: boolean;
  hasSession: boolean;
}

/** Mirrors the dashboard page gates: skeleton while pending, redirect effect when signed out. */
function SessionGate({ observations }: { observations: Observation[] }) {
  const { data, isPending } = useAuthSession();

  useEffect(() => {
    observations.push({ isPending, hasSession: data !== null });
  }, [isPending, data, observations]);

  if (isPending) return <p>loading</p>;
  return <p>{data ? `signed in as ${data.name}` : "signed out"}</p>;
}

const STORED_SESSION = {
  token: "token-abc",
  userId: "user-001",
  email: "jane@example.com",
  name: "Jane Doe",
};

let container: HTMLDivElement;
let root: Root | undefined;
const storage = new Map<string, string>();

// Node's own experimental localStorage global shadows jsdom's and is undefined without
// --localstorage-file, so install a Map-backed store (as session.test.ts does).
function installLocalStorage(): void {
  Object.defineProperty(window, "localStorage", {
    configurable: true,
    value: {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => void storage.set(key, value),
      removeItem: (key: string) => void storage.delete(key),
      clear: () => storage.clear(),
    },
  });
}

/** Prerenders with an empty store, as the static export build does. */
function prerender(observations: Observation[]): string {
  window.localStorage.clear();
  return renderToString(<SessionGate observations={observations} />);
}

async function hydrate(html: string, observations: Observation[]) {
  const recoverableErrors: unknown[] = [];
  container.innerHTML = html;
  await act(async () => {
    root = hydrateRoot(container, <SessionGate observations={observations} />, {
      onRecoverableError: (error) => recoverableErrors.push(error),
    });
  });
  return recoverableErrors;
}

describe("useAuthSession static-export hydration", () => {
  beforeEach(() => {
    globalThis.IS_REACT_ACT_ENVIRONMENT = true;
    installLocalStorage();
    window.localStorage.clear();
    container = document.createElement("div");
    document.body.appendChild(container);
  });

  afterEach(() => {
    act(() => root?.unmount());
    root = undefined;
    container.remove();
    storage.clear();
    vi.restoreAllMocks();
  });

  it("hydrates a stored session without a server/client mismatch", async () => {
    const html = prerender([]);
    saveAuthSession(STORED_SESSION);

    const recoverableErrors = await hydrate(html, []);

    expect(recoverableErrors).toEqual([]);
    expect(container.textContent).toBe("signed in as Jane Doe");
  });

  it("never lets a gate observe a resolved signed-out state while a session is stored", async () => {
    const html = prerender([]);
    saveAuthSession(STORED_SESSION);
    const observations: Observation[] = [];

    await hydrate(html, observations);

    expect(observations).not.toContainEqual({ isPending: false, hasSession: false });
    expect(observations.at(-1)).toEqual({ isPending: false, hasSession: true });
  });

  it("resolves to signed out when no session is stored", async () => {
    const html = prerender([]);
    const observations: Observation[] = [];

    const recoverableErrors = await hydrate(html, observations);

    expect(recoverableErrors).toEqual([]);
    expect(container.textContent).toBe("signed out");
    expect(observations.at(-1)).toEqual({ isPending: false, hasSession: false });
  });

  it("follows a sign-out after hydration", async () => {
    const html = prerender([]);
    saveAuthSession(STORED_SESSION);
    await hydrate(html, []);

    await act(async () => clearAuthSession());

    expect(container.textContent).toBe("signed out");
  });
});
