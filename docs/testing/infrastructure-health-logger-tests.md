# InfrastructureHealthLogger Unit Tests

## Overview

This document describes the comprehensive unit tests created for the `InfrastructureHealthLogger` components across all four services. The tests verify profile activation, dependency health checking, and logging behavior.

## Test Files Created

### 1. API Gateway
- **Test File**: `api-gateway/src/test/java/com/wealth/gateway/InfrastructureHealthLoggerTest.java`
- **Profile Test**: `api-gateway/src/test/java/com/wealth/gateway/InfrastructureHealthLoggerProfileTest.java`
- **Dependencies Tested**: Redis (Reactive)
- **Test Count**: 9 tests total (6 main + 3 profile)

### 2. Portfolio Service
- **Test File**: `portfolio-service/src/test/java/com/wealth/portfolio/InfrastructureHealthLoggerTest.java`
- **Profile Test**: `portfolio-service/src/test/java/com/wealth/portfolio/InfrastructureHealthLoggerProfileTest.java`
- **Dependencies Tested**: PostgreSQL, Kafka, Redis
- **Test Count**: 12 tests total (9 main + 3 profile)

### 3. Market Data Service
- **Test File**: `market-data-service/src/test/java/com/wealth/market/InfrastructureHealthLoggerTest.java`
- **Profile Test**: `market-data-service/src/test/java/com/wealth/market/InfrastructureHealthLoggerProfileTest.java`
- **Dependencies Tested**: MongoDB, Kafka
- **Test Count**: 12 tests total (9 main + 3 profile)

### 4. Insight Service
- **Test File**: `insight-service/src/test/java/com/wealth/insight/InfrastructureHealthLoggerTest.java`
- **Profile Test**: `insight-service/src/test/java/com/wealth/insight/InfrastructureHealthLoggerProfileTest.java`
- **Dependencies Tested**: Redis, Kafka
- **Test Count**: 12 tests total (9 main + 3 profile)

## Test Categories

### 1. Profile Activation Tests

Each service includes three profile activation tests to verify the component is loaded only under the correct profiles:

#### LocalProfileTest
- **Purpose**: Verify component is NOT loaded under `local` profile
- **Assertion**: `NoSuchBeanDefinitionException` is thrown when trying to get bean
- **Rationale**: Local Docker Compose environment is assumed healthy, no need for health checks

#### AwsProfileTest
- **Purpose**: Verify component IS loaded under `aws` profile
- **Assertion**: Bean exists and is not null
- **Rationale**: AWS deployments require infrastructure health monitoring

#### AzureProfileTest
- **Purpose**: Verify component IS loaded under `azure` profile
- **Assertion**: Bean exists and is not null
- **Rationale**: Azure deployments require infrastructure health monitoring

### 2. Connection Success Tests

Tests that verify successful dependency connections log the correct `[INFRA-OK]` messages:

#### API Gateway
- **Redis Success**: Verifies `[INFRA-OK] Redis — PONG received` with "rate-limiter backend ready"

#### Portfolio Service
- **PostgreSQL Success**: Verifies `[INFRA-OK] PostgreSQL — SELECT 1 succeeded` with "Neon/RDS reachable"
- **Kafka Success**: Verifies `[INFRA-OK] Kafka — broker reachable` with "market-prices topic accessible"
- **Redis Success**: Verifies `[INFRA-OK] Redis — PONG received` with "portfolio-analytics cache ready"

#### Market Data Service
- **MongoDB Success**: Verifies `[INFRA-OK] MongoDB — ping succeeded` with "Atlas/DocumentDB reachable"
- **Kafka Success**: Verifies `[INFRA-OK] Kafka — broker reachable` with "market-prices topic accessible"

#### Insight Service
- **Redis Success**: Verifies `[INFRA-OK] Redis — PONG received` with "insight cache ready"
- **Kafka Success**: Verifies `[INFRA-OK] Kafka — broker reachable` with "market-prices topic accessible"

### 3. Connection Failure Tests

Tests that verify failed dependency connections log the correct `[INFRA-FAIL]` messages with error details:

#### Common Failure Pattern
All failure tests verify:
1. `[INFRA-FAIL]` prefix in error log
2. Dependency name (Redis, PostgreSQL, Kafka, MongoDB)
3. Exception class name (e.g., `RuntimeException`, `IllegalStateException`)
4. Exception message (e.g., "Connection refused", "Timeout")
5. Helpful troubleshooting hint (e.g., "Check REDIS_URL", "Check KAFKA_BOOTSTRAP_SERVERS")

#### API Gateway Specific
- **Redis Timeout Test**: Simulates timeout using `Mono.never()` to verify timeout handling

#### Portfolio Service Specific
- Tests individual failures while other dependencies succeed
- Verifies PostgreSQL, Kafka, and Redis can fail independently

#### Market Data Service Specific
- MongoDB authentication failure test with `IllegalStateException`
- All dependencies fail scenario

#### Insight Service Specific
- Redis ping returns null scenario
- All dependencies fail scenario

### 4. Startup Banner Tests

All services verify:
- Opening banner: `=== Infrastructure connectivity check (SERVICE_NAME) ===`
- Closing banner: `=== Infrastructure connectivity check complete ===`
- Service name is included in logs

### 5. Mock Interaction Tests

All tests verify proper interaction with mocked dependencies:
- Connection factories are called appropriately
- Ping/query methods are invoked
- Kafka admin operations are executed

## Testing Techniques Used

### 1. Log Capturing
Uses Logback's `ListAppender` to capture log output:
```java
ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
logAppender.start();
Logger targetLogger = (Logger) LoggerFactory.getLogger(InfrastructureHealthLogger.class);
targetLogger.addAppender(logAppender);
```

### 2. Mockito Mocking
Mocks infrastructure dependencies to simulate success/failure scenarios:
- `@Mock` annotations for dependency injection
- `when().thenReturn()` for success scenarios
- `when().thenThrow()` for failure scenarios
- `Mono.never()` for timeout simulation (Reactive Redis)

### 3. AssertJ Assertions
Uses fluent assertions for readable test code:
- `assertThat(logMessages).anyMatch(msg -> msg.contains("..."))`
- `assertThatThrownBy(() -> ...).isInstanceOf(...)`
- `assertThat(bean).isNotNull()`

### 4. Spring Boot Test Context
Profile tests use `@SpringBootTest` and `@ActiveProfiles`:
```java
@SpringBootTest
@ActiveProfiles("aws")
static class AwsProfileTest { ... }
```

## Running the Tests

### All Tests
```bash
./gradlew test
```

### Individual Service
```bash
./gradlew :api-gateway:test
./gradlew :portfolio-service:test
./gradlew :market-data-service:test
./gradlew :insight-service:test
```

### Specific Test Class
```bash
./gradlew :api-gateway:test --tests InfrastructureHealthLoggerTest
./gradlew :api-gateway:test --tests InfrastructureHealthLoggerProfileTest
```

### Specific Test Method
```bash
./gradlew :api-gateway:test --tests InfrastructureHealthLoggerTest.onApplicationEvent_logsSuccess_whenRedisPongReceived
```

## Test Coverage Summary

| Service | Main Tests | Profile Tests | Total | Dependencies Covered |
|---------|-----------|---------------|-------|---------------------|
| API Gateway | 6 | 3 | 9 | Redis (Reactive) |
| Portfolio Service | 9 | 3 | 12 | PostgreSQL, Kafka, Redis |
| Market Data Service | 9 | 3 | 12 | MongoDB, Kafka |
| Insight Service | 9 | 3 | 12 | Redis, Kafka |
| **TOTAL** | **33** | **12** | **45** | 4 unique dependencies |

## Key Test Scenarios

### 1. Happy Path
- All dependencies healthy
- Correct `[INFRA-OK]` logs produced
- Startup banners logged
- Service name included

### 2. Partial Failures
- One dependency fails while others succeed
- Failed dependency logs `[INFRA-FAIL]`
- Successful dependencies log `[INFRA-OK]`
- Application continues startup

### 3. Complete Failures
- All dependencies fail
- Multiple `[INFRA-FAIL]` logs produced
- Application continues startup (non-blocking)

### 4. Timeout Handling
- Reactive Redis timeout (API Gateway)
- Graceful timeout handling
- `[INFRA-FAIL]` with timeout details

### 5. Profile Conditional Loading
- Component not loaded in `local` profile
- Component loaded in `aws` and `azure` profiles
- Bean lifecycle managed by Spring

## Design Patterns

### 1. Arrange-Act-Assert (AAA)
All tests follow the AAA pattern:
```java
// Arrange
when(redisConnection.ping()).thenReturn("PONG");

// Act
logger.onApplicationEvent(applicationReadyEvent);

// Assert
assertThat(logMessages).anyMatch(msg -> msg.contains("[INFRA-OK]"));
```

### 2. Test Isolation
- Each test sets up its own mocks
- Log appender cleaned up in `@AfterEach`
- No shared state between tests

### 3. Descriptive Test Names
Test names follow the pattern:
```
<methodName>_<expectedBehavior>_when<condition>
```

Examples:
- `onApplicationEvent_logsSuccess_whenRedisPongReceived`
- `onApplicationEvent_logsFailure_whenRedisUnreachable`
- `shouldNotLoadUnderLocalProfile`

## Maintenance Notes

### Adding New Tests
When adding new tests:
1. Follow the existing AAA pattern
2. Use descriptive test names
3. Add comments for test sections
4. Verify log messages with `anyMatch()`
5. Clean up resources in `@AfterEach`

### Modifying Dependencies
If infrastructure dependencies change:
1. Update corresponding mocks
2. Adjust log message assertions
3. Update this documentation

### Test Failures
Common causes of test failures:
1. **Log message changed**: Update assertions to match new message format
2. **Profile annotation changed**: Update profile activation tests
3. **New dependency added**: Add new test methods for the dependency
4. **Timeout changed**: Adjust timeout simulation if needed

## Best Practices Demonstrated

1. ✅ **Comprehensive Coverage**: Tests cover success, failure, and edge cases
2. ✅ **Log Verification**: Validates both INFO and ERROR logs
3. ✅ **Profile Testing**: Verifies conditional component loading
4. ✅ **Mock Isolation**: Each test has isolated mock setup
5. ✅ **Descriptive Names**: Clear test method naming convention
6. ✅ **Documentation**: Inline comments explain test purpose
7. ✅ **Cleanup**: Proper resource cleanup in `@AfterEach`
8. ✅ **Assertions**: Uses AssertJ for readable assertions
9. ✅ **No External Dependencies**: Tests run without infrastructure
10. ✅ **Fast Execution**: Unit tests run quickly without I/O

## Future Enhancements

Potential improvements:
1. Add parameterized tests for multiple exception types
2. Test concurrent health checks (if applicable)
3. Add integration tests with real infrastructure (Testcontainers)
4. Measure code coverage with JaCoCo
5. Add mutation testing with PITest
6. Test log format consistency across services
7. Add performance tests for health check duration

## Conclusion

The InfrastructureHealthLogger unit tests provide comprehensive coverage of:
- ✅ Profile-based conditional loading
- ✅ Successful infrastructure connectivity
- ✅ Failed infrastructure connectivity
- ✅ Timeout handling
- ✅ Error message formatting
- ✅ Startup banner logging
- ✅ Service identification

All tests are isolated, fast, and maintainable, following Spring Boot and JUnit 5 best practices.
