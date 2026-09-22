/**
 * Sanitization for the evidence files that are meant to be shareable (ledger, network
 * log, final state, provenance). URLs keep origin, path and query keys only. Every value
 * written is checked against registered secrets (passwords, tokens) and against JWT and
 * email shapes; a hit throws instead of writing.
 */
export interface SanitizedUrl {
  readonly origin: string;
  readonly path: string;
  readonly queryKeys: string[];
}

export function sanitizeUrl(url: string): SanitizedUrl {
  const parsed = new URL(url);
  return {
    origin: parsed.origin,
    path: parsed.pathname,
    queryKeys: [...new Set(parsed.searchParams.keys())].sort(),
  };
}

const JWT_SHAPE = /eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}/;
const EMAIL_SHAPE = /[^\s@"']+@[^\s@"']+\.[^\s@"']+/;
const MIN_SECRET_LENGTH = 6;

export class SecretRegistry {
  private readonly secrets = new Set<string>();

  register(secret: string | undefined | null): void {
    if (typeof secret === "string" && secret.length >= MIN_SECRET_LENGTH) this.secrets.add(secret);
  }

  assertClean(value: unknown): void {
    const visit = (node: unknown): void => {
      if (typeof node === "string") {
        for (const secret of this.secrets) {
          if (node.includes(secret)) throw new Error("evidence value contains a registered secret");
        }
        if (JWT_SHAPE.test(node)) throw new Error("evidence value contains a JWT-shaped string");
        if (EMAIL_SHAPE.test(node)) throw new Error("evidence value contains an email address");
        return;
      }
      if (Array.isArray(node)) {
        node.forEach(visit);
        return;
      }
      if (node !== null && typeof node === "object") {
        for (const [key, child] of Object.entries(node)) {
          visit(key);
          visit(child);
        }
      }
    };
    visit(value);
  }
}
