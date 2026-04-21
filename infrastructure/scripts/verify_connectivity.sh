#!/bin/bash
# Pre-Flight Connectivity Verification Script
# Verifies network reachability and basic SSL handshakes for all 3rd party dependencies.

set -e

echo "================================================================"
echo " Starting Pre-Flight Connectivity Checks"
echo "================================================================"

# 1. PostgreSQL (Neon)
echo "Checking PostgreSQL (Neon) reachability..."
# Extract host from JDBC URL: jdbc:postgresql://host:port/db or jdbc:postgresql://host/db
DB_HOST=$(echo $POSTGRES_CONNECTION_STRING | sed -e 's/jdbc:postgresql:\/\///' -e 's/[:\/].*//')
if [ -z "$DB_HOST" ]; then echo "ERROR: POSTGRES_CONNECTION_STRING is malformed"; exit 1; fi
nc -zv -w 10 "$DB_HOST" 5432 || { echo "CRITICAL: Cannot reach PostgreSQL at $DB_HOST:5432. Check VPC/Security Groups or Neon status."; exit 1; }
echo "PostgreSQL reachable."

# 2. MongoDB (Atlas)
echo "Checking MongoDB (Atlas) reachability..."
# Extract host from srv URI: mongodb+srv://user:pass@host/db
MONGO_BASE_HOST=$(echo $MONGODB_CONNECTION_STRING | sed -e 's/mongodb+srv:\/\/.*@//' -e 's/\/.*//')
if [ -z "$MONGO_BASE_HOST" ]; then echo "ERROR: MONGODB_CONNECTION_STRING is malformed"; exit 1; fi

echo "Resolving MongoDB SRV record for $MONGO_BASE_HOST..."
# Attempt to find actual node hostnames from SRV record
# We use nslookup or dig to find the first node.
# The awk '{print $NF}' extracts the last column (the hostname).
# The sed 's/\.$//' removes the trailing dot if present.
SRV_NODE=$(nslookup -type=SRV _mongodb._tcp."$MONGO_BASE_HOST" | grep "service =" | head -n 1 | awk '{print $NF}' | sed 's/\.$//')

if [ -z "$SRV_NODE" ]; then
    # Fallback to dig if nslookup output is unexpected
    SRV_NODE=$(dig +short SRV _mongodb._tcp."$MONGO_BASE_HOST" | head -n 1 | awk '{print $NF}' | sed 's/\.$//')
fi

if [ -z "$SRV_NODE" ]; then
    echo "WARNING: Could not resolve SRV record for $MONGO_BASE_HOST. Defaulting to base host (may fail if no A-record)."
    SRV_NODE="$MONGO_BASE_HOST"
fi

echo "Probing node: $SRV_NODE:27017"
nc -zv -w 10 "$SRV_NODE" 27017 || { echo "CRITICAL: Cannot reach MongoDB at $SRV_NODE:27017. Check Atlas Network Access/IP Whitelist."; exit 1; }
echo "MongoDB reachable."

# 3. Redis (Upstash)
echo "Checking Redis (Upstash) reachability..."
# Format: rediss://[:password@]host:port
REDIS_HOST=$(echo $REDIS_URL | sed -e 's/rediss:\/\///' -e 's/.*@//' -e 's/:.*//')
REDIS_PORT=$(echo $REDIS_URL | sed -e 's/.*://')
if [ -z "$REDIS_HOST" ]; then echo "ERROR: REDIS_URL is malformed"; exit 1; fi
nc -zv -w 10 "$REDIS_HOST" "$REDIS_PORT" || { echo "CRITICAL: Cannot reach Redis at $REDIS_HOST:$REDIS_PORT."; exit 1; }
echo "Redis reachable."

# 4. Kafka (Aiven/Confluent)
echo "Checking Kafka reachability and SSL Handshake..."
# host:port
KAFKA_HOST=$(echo $KAFKA_BOOTSTRAP_SERVERS | cut -d',' -f1 | cut -d':' -f1)
KAFKA_PORT=$(echo $KAFKA_BOOTSTRAP_SERVERS | cut -d',' -f1 | cut -d':' -f2)
if [ -z "$KAFKA_HOST" ]; then echo "ERROR: KAFKA_BOOTSTRAP_SERVERS is malformed"; exit 1; fi

# Network check
nc -zv -w 10 "$KAFKA_HOST" "$KAFKA_PORT" || { echo "CRITICAL: Cannot reach Kafka at $KAFKA_HOST:$KAFKA_PORT."; exit 1; }

# SSL Handshake check (Targeting the PKIX issue)
echo "Verifying Kafka SSL Handshake..."
if ! echo "Q" | openssl s_client -connect "$KAFKA_HOST:$KAFKA_PORT" -servername "$KAFKA_HOST" > /dev/null 2>&1; then
    echo "CRITICAL: Kafka SSL Handshake failed. This will cause PKIX errors in Java."
    exit 1
fi
echo "Kafka SSL Handshake successful."

echo "================================================================"
echo " SUCCESS: All Pre-Flight Checks Passed!"
echo "================================================================"
