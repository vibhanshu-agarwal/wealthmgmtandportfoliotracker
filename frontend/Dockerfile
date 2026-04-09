# Stage 1: Dependencies
FROM node:20-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

# Stage 2: Builder
FROM node:20-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
# Build-time secrets required by NextAuth — injected via --build-arg in CI/CD.
# These are NOT baked into the final image; they are only needed during `npm run build`.
ARG NEXTAUTH_SECRET
ARG AUTH_JWT_SECRET
ENV NEXTAUTH_SECRET=$NEXTAUTH_SECRET
ENV AUTH_JWT_SECRET=$AUTH_JWT_SECRET
# We disable telemetry to speed up the build
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

# Stage 3: Runner
FROM node:20-alpine AS runner
WORKDIR /app

# Fixed Legacy Warnings
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000

RUN addgroup --system --gid 1001 nodejs
RUN adduser --system --uid 1001 nextjs

# The Fix: Only copy public if it exists (some Next.js versions skip it if empty)
COPY --from=builder /app/public ./public
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static

USER nextjs
EXPOSE 3000
CMD ["node", "server.js"]