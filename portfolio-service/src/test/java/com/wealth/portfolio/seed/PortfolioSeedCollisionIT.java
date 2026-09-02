package com.wealth.portfolio.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wealth.portfolio.AssetHolding;
import com.wealth.portfolio.AssetHoldingRepository;
import com.wealth.portfolio.GlobalExceptionHandler;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.TestContainerImages;
import com.wealth.portfolio.composition.CompositionResult;
import com.wealth.portfolio.composition.GoldenStateTuplePreparer;
import com.wealth.portfolio.composition.HoldingReplacementService;
import com.wealth.portfolio.composition.PortfolioVersionConflictException;
import com.wealth.portfolio.composition.RawIntent;
import com.wealth.portfolio.composition.TuplePreparer;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Task 6.3: symmetric arbitration between the real internal seed and a concurrent user edit.
 *
 * <p>One contender is always the production {@link PortfolioSeedService#seed(String, long)} path,
 * reached for the losing case through actual HTTP dispatch to the real
 * {@link PortfolioSeedController} and {@link GlobalExceptionHandler}. Two bare
 * {@link HoldingReplacementService} calls would only repeat Wave 4's proof and could not show
 * that the new adapter carries the caller's observed version into the transaction unchanged.
 *
 * <p>Ordering is forced with bounded latches, never sleeps: both writers enter {@code replace}
 * carrying the same observed version {@code N}, one is parked at a barrier while the other
 * commits, and the parked writer is then released to meet the advanced version. Attempts are
 * counted independently of the latches so a hidden retry cannot hide behind the coordination.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class PortfolioSeedCollisionIT {

    private static final String INTERNAL_KEY = "test-internal-key";
    private static final String SEED_PATH = "/api/internal/portfolio/seed";
    private static final String E2E_USER_ID = "00000000-0000-0000-0000-000000000e2e";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(TestContainerImages.POSTGRES)
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    /** Spied so a barrier can be installed around the transaction the real seed delegates to. */
    @MockitoSpyBean HoldingReplacementService replacementService;

    @Autowired PortfolioSeedService seedService;
    @Autowired PortfolioSeedController seedController;
    @Autowired GlobalExceptionHandler globalExceptionHandler;
    @Autowired PortfolioRepository portfolioRepository;
    @Autowired AssetHoldingRepository assetHoldingRepository;
    /** Spied so both creators can be held inside materialise, mid-transaction. */
    @MockitoSpyBean SeedTickerRegistry registry;
    @Autowired DemoProperties demoProperties;
    @Autowired JdbcTemplate jdbc;

    private MockMvc seedMvc;
    private MockMvc editMvc;

    /** Counts real replacement attempts, independently of any latch. */
    private final AtomicInteger replaceAttempts = new AtomicInteger();

    /** How many further delegations must park before delegating; each call consumes one. */
    private final AtomicInteger barrierParksRemaining = new AtomicInteger();

    /** Armed only for the absent-creation race; see {@link #armAbsenceBarrier}. */
    private final AtomicBoolean registryBarrierArmed = new AtomicBoolean(false);

    /** A thread parks at most once, however many times it consults the registry. */
    private final ThreadLocal<Boolean> parkedOnThisThread = ThreadLocal.withInitial(() -> false);
    private final AtomicReference<CountDownLatch> barrierParked = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> barrierRelease = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        replaceAttempts.set(0);
        barrierParksRemaining.set(0);
        registryBarrierArmed.set(false);

        // Counting is installed for every test, not only the ones that park, so a hidden retry
        // cannot go unobserved in a case that happens not to arm a barrier.
        doAnswer(
                        invocation -> {
                            replaceAttempts.incrementAndGet();
                            if (barrierParksRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                                awaitBarrier();
                            }
                            return invocation.callRealMethod();
                        })
                .when(replacementService)
                .replace(anyString(), anyLong(), any(), any());

        seedMvc =
                MockMvcBuilders.standaloneSetup(seedController)
                        .addFilter(new InternalApiKeyFilter(INTERNAL_KEY))
                        .setControllerAdvice(globalExceptionHandler)
                        .build();
        editMvc =
                MockMvcBuilders.standaloneSetup(new TestOnlyEditController(replacementService))
                        .setControllerAdvice(globalExceptionHandler)
                        .build();
    }

    // ────────────────────────────── present aggregate, changed tuple ──────────────────────────

    @Test
    void seedCommitsFirst_thenTheUserEditLosesWithoutPartialHoldings() throws Exception {
        Fixture fixture = givenDivergedPortfolio();
        CountDownLatch editParked = new CountDownLatch(1);
        CountDownLatch releaseEdit = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> editError = new AtomicReference<>();
        AtomicReference<CompositionResult> editOutcome = new AtomicReference<>();
        try {
            // The edit enters the transaction carrying N, then parks before its own CAS.
            Future<?> editThread =
                    pool.submit(
                            () -> {
                                try {
                                    editOutcome.set(
                                            replacementService.replace(
                                                    fixture.userId(),
                                                    fixture.version(),
                                                    userEditIntent(fixture),
                                                    parkingPreparer(
                                                            userEditPreparer(fixture),
                                                            editParked,
                                                            releaseEdit)));
                                } catch (Throwable t) {
                                    editError.set(t);
                                }
                                return null;
                            });

            assertThat(editParked.await(30, TimeUnit.SECONDS))
                    .as("the edit must be in flight, holding the same observed version")
                    .isTrue();

            // The seed runs to completion and commits while the edit is parked.
            seedService.seed(fixture.userId(), fixture.version());

            releaseEdit.countDown();
            editThread.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(editOutcome.get()).as("the edit must not have committed").isNull();
        assertThat(editError.get()).isInstanceOf(PortfolioVersionConflictException.class);

        assertExactlyOneTransition(fixture);
        assertGoldenTuplePersisted(fixture);
        assertNoHoldingFromTheLosingEdit(fixture);
        assertThat(replaceAttempts.get())
                .as("two contenders, two real attempts: the loser must not retry")
                .isEqualTo(2);
    }

    @Test
    void userEditCommitsFirst_thenTheSeedLosesOverHttpWith409() throws Exception {
        // The endpoint always seeds its compiled-in E2E target, so the contested aggregate must
        // be that user's. Racing a randomly-named user here produced a 409 for the wrong reason:
        // the seed found no aggregate at all and reported currentVersion 0, and no race occurred.
        Fixture fixture = givenDivergedPortfolio(E2E_USER_ID);
        CountDownLatch seedParked = new CountDownLatch(1);
        CountDownLatch releaseSeed = new CountDownLatch(1);
        armSeedBarrier(seedParked, releaseSeed);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Integer> seedStatus = new AtomicReference<>();
        AtomicReference<String> seedBody = new AtomicReference<>();
        try {
            // The seed enters its transaction carrying N through real HTTP dispatch, then parks.
            Future<?> seedThread =
                    pool.submit(
                            () -> {
                                var response =
                                        seedMvc.perform(
                                                        post(SEED_PATH)
                                                                .header(
                                                                        "X-Internal-Api-Key",
                                                                        INTERNAL_KEY)
                                                                .contentType(
                                                                        MediaType.APPLICATION_JSON)
                                                                .content(
                                                                        "{\"expectedVersion\":"
                                                                                + fixture.version()
                                                                                + "}"))
                                                .andReturn()
                                                .getResponse();
                                seedStatus.set(response.getStatus());
                                seedBody.set(response.getContentAsString());
                                return null;
                            });

            assertThat(seedParked.await(30, TimeUnit.SECONDS))
                    .as("the seed must be in flight, holding the same observed version")
                    .isTrue();

            // The user edit commits while the seed is parked.
            CompositionResult edit =
                    replacementService.replace(
                            fixture.userId(),
                            fixture.version(),
                            userEditIntent(fixture),
                            userEditPreparer(fixture));
            assertThat(edit.version()).isEqualTo(fixture.version() + 1);

            releaseSeed.countDown();
            seedThread.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(seedStatus.get())
                .as("a losing seed must be a conflict, never a 404 or a raw 500")
                .isEqualTo(409);
        assertThat(seedBody.get()).contains("\"error\":\"portfolio_version_conflict\"");
        assertThat(seedBody.get()).contains("\"currentVersion\":" + (fixture.version() + 1));
        assertThat(seedBody.get()).contains("\"message\"");

        assertExactlyOneTransition(fixture);
        assertUserEditTuplePersisted(fixture);
        assertThat(replaceAttempts.get())
                .as("two contenders, two real attempts: the loser must not retry")
                .isEqualTo(2);
    }

    /**
     * The losing side of the symmetric case, proved through the same HTTP advice a user-facing
     * route would use. The harness controller exists only in test sources; no production route
     * is introduced here.
     */
    @Test
    void losingUserEditSurfacesTheSameConflictEnvelopeOverHttp() throws Exception {
        Fixture fixture = givenDivergedPortfolio();

        seedService.seed(fixture.userId(), fixture.version());

        editMvc.perform(
                        post("/test-only/composition/" + fixture.userId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":" + fixture.version() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("portfolio_version_conflict"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.currentVersion").value((int) (fixture.version() + 1)));
    }

    /**
     * Equality must never rescue a stale caller. The user commits a tuple that happens to equal
     * golden, so by the time the frozen seed arrives its desired state already matches — and it
     * must still be rejected on version, not reported as a successful no-op.
     */
    @Test
    void staleSeedConflictsEvenWhenTheCommittedTupleAlreadyEqualsGolden() {
        Fixture fixture = givenDivergedPortfolio();
        long frozen = fixture.version();

        // The user converges to exactly the golden tuple, advancing the version.
        CompositionResult userWrite =
                replacementService.replace(
                        fixture.userId(),
                        frozen,
                        List.of(),
                        new GoldenStateTuplePreparer(
                                registry, fixture.userId(), demoProperties.costBasisAnchor()));
        assertThat(userWrite.version()).isEqualTo(frozen + 1);

        int attemptsBefore = replaceAttempts.get();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> seedService.seed(fixture.userId(), frozen))
                .as("a matching tuple must not turn a stale version into success")
                .isInstanceOf(PortfolioVersionConflictException.class);

        assertThat(replaceAttempts.get() - attemptsBefore)
                .as("the losing seed must not retry")
                .isEqualTo(1);
        assertThat(currentVersion(fixture.portfolioId())).isEqualTo(frozen + 1);
    }

    // ────────────────────────────────── absent aggregate race ─────────────────────────────────

    /**
     * One contender is the real {@link PortfolioSeedService#seed(String, long)}; the other is a
     * concurrent creator. Two bare replacement calls here would only re-prove Wave 4 and could
     * not show that the seed adapter carries expected version zero into the creation path.
     */
    @Test
    void twoAbsentCreatorsProduceOneAggregateAtVersionOneAndOneConflict() throws Exception {
        String userId = UUID.randomUUID().toString();
        deletePortfolios(userId);
        CountDownLatch bothArrived = new CountDownLatch(2);
        CountDownLatch proceed = new CountDownLatch(1);
        armAbsenceBarrier(bothArrived, proceed);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<PortfolioSeedService.SeedResult> seedOutcome = new AtomicReference<>();
        AtomicReference<Throwable> seedError = new AtomicReference<>();
        AtomicReference<CompositionResult> rivalOutcome = new AtomicReference<>();
        AtomicReference<Throwable> rivalError = new AtomicReference<>();
        try {
            Future<?> seedThread =
                    pool.submit(
                            () -> {
                                try {
                                    seedOutcome.set(seedService.seed(userId, 0L));
                                } catch (Throwable t) {
                                    seedError.set(t);
                                }
                                return null;
                            });
            Future<?> rivalThread =
                    pool.submit(
                            () -> {
                                try {
                                    rivalOutcome.set(
                                            replacementService.replace(
                                                    userId,
                                                    0L,
                                                    List.of(),
                                                    new GoldenStateTuplePreparer(
                                                            registry,
                                                            userId,
                                                            demoProperties.costBasisAnchor())));
                                } catch (Throwable t) {
                                    rivalError.set(t);
                                }
                                return null;
                            });

            assertThat(bothArrived.await(30, TimeUnit.SECONDS))
                    .as("both creators must have observed absence and be poised to insert")
                    .isTrue();
            proceed.countDown();
            seedThread.get(60, TimeUnit.SECONDS);
            rivalThread.get(60, TimeUnit.SECONDS);
        } finally {
            registryBarrierArmed.set(false);
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        int winners = (seedOutcome.get() != null ? 1 : 0) + (rivalOutcome.get() != null ? 1 : 0);
        int losers = (seedError.get() != null ? 1 : 0) + (rivalError.get() != null ? 1 : 0);
        assertThat(winners).as("exactly one creator may win").isEqualTo(1);
        assertThat(losers).isEqualTo(1);

        Throwable loser = seedError.get() != null ? seedError.get() : rivalError.get();
        assertThat(loser)
                .as("the named uniqueness conflict must be resolved into a version conflict, "
                        + "never surfaced as an unrelated integrity failure")
                .isInstanceOf(PortfolioVersionConflictException.class);

        // These two assertions are what make this case a uniqueness-race proof rather than a
        // stale-version one. If the barrier ever stopped holding both creators in the window
        // between observing absence and inserting, the loser would take the present-aggregate
        // path and arrive carrying a known version, and this would fail rather than pass
        // quietly on the strength of the outcome counts alone.
        PortfolioVersionConflictException conflict = (PortfolioVersionConflictException) loser;
        assertThat(conflict.currentVersion())
                .as("the uniqueness loser cannot know the version until after its own rollback")
                .isEmpty();
        assertThat(conflict.lookupUserId())
                .as("it must carry the contested user for the post-rollback re-read")
                .contains(userId);

        assertThat(replaceAttempts.get())
                .as("two contenders, two real attempts: the loser must not retry")
                .isEqualTo(2);

        List<Portfolio> portfolios = portfolioRepository.findByUserId(userId);
        assertThat(portfolios).as("exactly one aggregate survives the race").hasSize(1);
        assertThat(portfolios.get(0).getVersion()).isEqualTo(1L);
        assertThat(holdingCount(portfolios.get(0).getId())).isEqualTo(registry.active().size());

        // The loser reports the committed version only after its own rollback, through the real
        // advice. Asserted here rather than over HTTP because either contender may lose.
        ResponseEntity<com.wealth.portfolio.composition.ContractError> response =
                globalExceptionHandler.handlePortfolioVersionConflict(
                        (PortfolioVersionConflictException) loser);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().name()).isEqualTo("portfolio_version_conflict");
        assertThat(response.getBody().currentVersion())
                .as("post-rollback re-read must report the winner's committed version")
                .isEqualTo(1L);
    }

    // ────────────────────────────────────── helpers ───────────────────────────────────────────

    /**
     * Arms a one-shot barrier on the next delegation the real seed makes.
     *
     * <p>The park happens around the delegation rather than inside the supplied preparer:
     * substituting the preparer on the invocation does not reach {@code callRealMethod}: the
     * spy binds the real method's arguments at interception, so a later write to the
     * invocation's argument array is inert. Parking here is equivalent for this proof and
     * strictly more
     * faithful about what is in flight — {@link PortfolioSeedService#seed(String, long)} is
     * itself {@code @Transactional}, so its transaction is already open and holding a connection
     * while the thread waits. The real transaction and persistence still do all the work; only
     * the coordination is test-only.
     */
    private void armSeedBarrier(CountDownLatch parked, CountDownLatch release) {
        armBarrier(parked, release, 1);
    }

    /**
     * Holds every creator inside {@code materialise}, which {@code replaceAbsent} runs after
     * observing absence and before {@code saveAndFlush} — the only window in which two creators
     * genuinely contend for the named uniqueness constraint. Parking any earlier lets one
     * creator commit before the other reads, degrading the case into an ordinary stale-version
     * conflict that the outcome counts alone would not distinguish.
     *
     * <p>The park is placed in {@link SeedTickerRegistry#active()} because the preparer calls it
     * there. Substituting a barrier-wrapped preparer through the replacement-service spy is not
     * possible: a Mockito spy binds the real method's arguments at interception, so writing
     * to the invocation's argument array is inert by the time {@code callRealMethod()}
     * runs. This is not a difference between the raw and expanded argument views - for an
     * ordinary non-varargs signature those are the same array - so no accessor choice
     * makes substitution work.
     */
    private void armAbsenceBarrier(CountDownLatch parked, CountDownLatch release) {
        barrierParked.set(parked);
        barrierRelease.set(release);
        registryBarrierArmed.set(true);
        doAnswer(
                        invocation -> {
                            Object result = invocation.callRealMethod();
                            if (registryBarrierArmed.get()
                                    && !Boolean.TRUE.equals(parkedOnThisThread.get())) {
                                parkedOnThisThread.set(true);
                                awaitBarrier();
                            }
                            return result;
                        })
                .when(registry)
                .active();
    }

    /**
     * Parks the next {@code parks} delegations at a shared barrier, so two contenders meet at the
     * same point rather than one being structurally ahead of the other.
     */
    private void armBarrier(CountDownLatch parked, CountDownLatch release, int parks) {
        barrierParked.set(parked);
        barrierRelease.set(release);
        barrierParksRemaining.set(parks);
    }

    private void awaitBarrier() {
        CountDownLatch parked = barrierParked.get();
        CountDownLatch release = barrierRelease.get();
        parked.countDown();
        try {
            assertThat(release.await(30, TimeUnit.SECONDS))
                    .as("barrier must be released, not timed out")
                    .isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted at the arbitration barrier", e);
        }
    }

    private TuplePreparer parkingPreparer(
            TuplePreparer delegate, CountDownLatch arrived, CountDownLatch proceed) {
        return (intent, locked) -> {
            arrived.countDown();
            try {
                assertThat(proceed.await(30, TimeUnit.SECONDS))
                        .as("barrier must be released, not timed out")
                        .isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted at the arbitration barrier", e);
            }
            return delegate.materialise(intent, locked);
        };
    }

    private Fixture givenDivergedPortfolio() {
        return givenDivergedPortfolio(UUID.randomUUID().toString());
    }

    /** Establishes a golden portfolio, then diverges its stored tuple without touching version. */
    private Fixture givenDivergedPortfolio(String userId) {
        deletePortfolios(userId);
        var created = seedService.seed(userId, 0L);
        UUID portfolioId = created.portfolioId();

        // Diverge so neither contender's desired tuple equals the starting tuple.
        jdbc.update(
                "UPDATE asset_holdings SET quantity = quantity + 7 WHERE portfolio_id = ?::uuid",
                portfolioId.toString());

        long version = currentVersion(portfolioId);
        replaceAttempts.set(0);
        return new Fixture(userId, portfolioId, version, firstActiveTicker());
    }

    private static final BigDecimal USER_EDIT_TICKER_QUANTITY = new BigDecimal("3.00000000");
    private static final BigDecimal USER_EDIT_COST_BASIS = new BigDecimal("1.0000");

    /** A user edit that is neither the starting tuple nor the golden tuple. */
    private List<RawIntent> userEditIntent(Fixture fixture) {
        return List.of(new RawIntent(fixture.ticker(), USER_EDIT_TICKER_QUANTITY));
    }

    private TuplePreparer userEditPreparer(Fixture fixture) {
        return (intent, locked) ->
                intent.stream()
                        .map(
                                item ->
                                        new com.wealth.portfolio.composition.DesiredHoldingState(
                                                item.ticker(),
                                                item.quantity(),
                                                USER_EDIT_COST_BASIS,
                                                "USD",
                                                "USER",
                                                demoProperties.costBasisAnchor()))
                        .toList();
    }

    private void assertExactlyOneTransition(Fixture fixture) {
        assertThat(currentVersion(fixture.portfolioId()))
                .as("exactly one committed transition from two contenders at version %s",
                        fixture.version())
                .isEqualTo(fixture.version() + 1);
        assertThat(portfolioRepository.findByUserId(fixture.userId()))
                .as("the aggregate identity must be stable across the race")
                .singleElement()
                .extracting(Portfolio::getId)
                .isEqualTo(fixture.portfolioId());
    }

    /**
     * Every field of every holding, against the deterministic desired tuple. A count plus a
     * source check would pass even if the winner had persisted the wrong quantity or a stale
     * cost-basis anchor.
     */
    private void assertGoldenTuplePersisted(Fixture fixture) {
        assertThat(holdingCount(fixture.portfolioId())).isEqualTo(registry.active().size());

        Map<String, PortfolioSeedService.DesiredHolding> desired =
                seedService.desiredHoldings(fixture.userId()).stream()
                        .collect(
                                java.util.stream.Collectors.toUnmodifiableMap(
                                        PortfolioSeedService.DesiredHolding::ticker, d -> d));
        List<AssetHolding> holdings = holdings(fixture.portfolioId());

        assertThat(holdings).hasSize(desired.size());
        for (AssetHolding holding : holdings) {
            PortfolioSeedService.DesiredHolding want = desired.get(holding.getAssetTicker());
            assertThat(want).as("unexpected ticker %s", holding.getAssetTicker()).isNotNull();
            assertThat(holding.getQuantity())
                    .as("quantity for %s", holding.getAssetTicker())
                    .isEqualByComparingTo(want.quantity());
            assertThat(holding.getAvgCostBasis())
                    .as("avgCostBasis for %s", holding.getAssetTicker())
                    .isEqualByComparingTo(want.avgCostBasis());
            assertThat(holding.getCostBasisCurrency())
                    .as("costBasisCurrency for %s", holding.getAssetTicker())
                    .isEqualTo(want.costBasisCurrency());
            assertThat(holding.getCostBasisSource())
                    .as("costBasisSource for %s", holding.getAssetTicker())
                    .isEqualTo(want.costBasisSource());
            assertThat(holding.getCostBasisAsOf())
                    .as("costBasisAsOf for %s", holding.getAssetTicker())
                    .isEqualTo(want.costBasisAsOf());
        }
    }

    /** Every field of the winning edit's holding, matching {@link #userEditPreparer}. */
    private void assertUserEditTuplePersisted(Fixture fixture) {
        List<AssetHolding> holdings = holdings(fixture.portfolioId());
        assertThat(holdings)
                .as("the winning edit replaces the whole set with its own single holding")
                .singleElement()
                .satisfies(
                        h -> {
                            assertThat(h.getAssetTicker()).isEqualTo(fixture.ticker());
                            assertThat(h.getQuantity())
                                    .isEqualByComparingTo(USER_EDIT_TICKER_QUANTITY);
                            assertThat(h.getAvgCostBasis())
                                    .isEqualByComparingTo(USER_EDIT_COST_BASIS);
                            assertThat(h.getCostBasisCurrency()).isEqualTo("USD");
                            assertThat(h.getCostBasisSource()).isEqualTo("USER");
                            assertThat(h.getCostBasisAsOf())
                                    .isEqualTo(demoProperties.costBasisAnchor());
                        });
    }

    /**
     * Keyed on the cost-basis source rather than the quantity: golden quantities are
     * {@code hash mod 50 + 1}, so a legitimate seeded holding could coincidentally equal the
     * edit's quantity and make a quantity-based check pass for the wrong reason.
     */
    private void assertNoHoldingFromTheLosingEdit(Fixture fixture) {
        assertThat(holdings(fixture.portfolioId()))
                .as("no partial holding from the losing writer may survive")
                .noneSatisfy(h -> assertThat(h.getCostBasisSource()).isEqualTo("USER"));
    }

    private List<AssetHolding> holdings(UUID portfolioId) {
        return assetHoldingRepository.findByPortfolio(
                portfolioRepository.findById(portfolioId).orElseThrow());
    }

    private int holdingCount(UUID portfolioId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM asset_holdings WHERE portfolio_id = ?::uuid",
                        Integer.class,
                        portfolioId.toString());
        return count == null ? 0 : count;
    }

    private long currentVersion(UUID portfolioId) {
        Long version =
                jdbc.queryForObject(
                        "SELECT version FROM portfolios WHERE id = ?::uuid",
                        Long.class,
                        portfolioId.toString());
        return version == null ? 0L : version;
    }

    /** Shared targets such as the E2E user must not inherit another case's aggregate. */
    private void deletePortfolios(String userId) {
        jdbc.update(
                "DELETE FROM asset_holdings WHERE portfolio_id IN "
                        + "(SELECT id FROM portfolios WHERE user_id = ?)",
                userId);
        jdbc.update("DELETE FROM portfolios WHERE user_id = ?", userId);
    }

    private String firstActiveTicker() {
        return registry.active().get(0).ticker();
    }

    private record Fixture(String userId, UUID portfolioId, long version, String ticker) {}

    /**
     * Test-only route so a losing user-edit reaches the same {@link GlobalExceptionHandler}
     * advice a future user-facing endpoint would. Deliberately not a production controller and
     * not the Wave 7 route.
     */
    @RestController
    @RequestMapping("/test-only/composition")
    static class TestOnlyEditController {

        private final HoldingReplacementService replacementService;

        TestOnlyEditController(HoldingReplacementService replacementService) {
            this.replacementService = replacementService;
        }

        @PostMapping("/{userId}")
        ResponseEntity<Map<String, Object>> edit(
                @org.springframework.web.bind.annotation.PathVariable String userId,
                @Valid @RequestBody PortfolioSeedRequest request) {
            CompositionResult result =
                    replacementService.replace(
                            userId,
                            request.expectedVersion(),
                            List.of(),
                            (intent, locked) -> List.of());
            return ResponseEntity.ok(Map.of("version", result.version()));
        }
    }
}
