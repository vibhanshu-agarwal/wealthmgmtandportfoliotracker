---
description: Redis database management. Key-value operations, caching, pub/sub, and data structure commands.
metadata:
    clawdbot:
        always: true
        emoji: "\U0001F534"
        requires:
            bins:
                - curl
                - jq
    github-path: redis
    github-ref: refs/heads/main
    github-repo: https://github.com/zangxin75/openclaw-skills
    github-tree-sha: a629a1dbf4c0fd9c0ed3749980100b1707bf2f72
name: redis
---
# Redis 🔴

Redis in-memory database management.

## Setup

```bash
export REDIS_URL="redis://localhost:6379"
```

## Features

- Key-value operations
- Data structures (lists, sets, hashes)
- Pub/Sub messaging
- Cache management
- TTL management

## Usage Examples

```
"Get key user:123"
"Set cache for 1 hour"
"Show all keys matching user:*"
"Flush cache"
```

## Commands

```bash
redis-cli GET key
redis-cli SET key value EX 3600
redis-cli KEYS "pattern*"
```
