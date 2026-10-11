package io.github.marekpompura.db2mcp.configuration;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replaces Spring AI's default {@code stdioServerTransport} bean (ADR 0016)
 * so the process exits when its MCP client closes stdin, instead of
 * surviving as an orphaned process forever.
 *
 * <p>{@code spring.main.keep-alive: true} (ADR 0001) keeps the JVM alive
 * with a dedicated non-daemon thread, entirely independent of whether the
 * MCP session is still open - a real gap found while verifying this: the
 * SDK's {@code StdioServerTransportProvider} already detects stdin EOF and
 * closes its own protocol session internally, but nothing was tying that
 * closure to the keep-alive thread or the JVM actually exiting, so a
 * disconnected client left this process running forever, consuming a
 * thread and a process slot for nothing.
 *
 * <p>A second, later-found gap (ADR 0031): shutting down immediately on EOF
 * raced any tool call still executing. The SDK's own inbound reader thread
 * and outbound writer thread are independent; on EOF, the reader
 * unconditionally sets a shared {@code isClosing} flag, and the writer
 * checks that same flag before writing each queued response - a response
 * still in flight at that instant is silently dropped ({@code
 * sink.complete()} instead of being written), no matter how long this
 * bean's own shutdown sequence waits afterward. Real MCP clients don't
 * close stdin with a request outstanding, which is why this went unnoticed
 * until a live-DB2 verification session reproduced it directly.
 *
 * <p>The fix that actually closes it: {@link EofTriggeredShutdownInputStream}
 * fires its callback synchronously, from inside the {@code read()} call
 * that first observes EOF, before that call returns -1 to the SDK. Making
 * that callback block - waiting out {@link InFlightRequestTracker} - means
 * the SDK's reader thread doesn't observe EOF, and doesn't set its own
 * {@code isClosing}, until every request already read has a response
 * already written. The SDK never even learns EOF happened until it's safe
 * to know.
 *
 * <p>An earlier version of this fix tracked in-flight calls by a plain
 * count ({@code InFlightToolCallTracker}, incremented/decremented around
 * {@code ToolCallback.call(...)}), gated by a fixed {@code
 * DISPATCH_SETTLE_DELAY} guessing how long the SDK's reactive
 * scheduler-handoff from "request read" to "tool dispatched" could take.
 * Verified passing repeatedly, then observed to still fail, intermittently,
 * on a contended CI runner - the handoff occasionally took longer than the
 * guess. This version replaces that guess with an exact signal: {@link
 * RequestIdTrackingInputStream} and {@link ResponseIdTrackingOutputStream}
 * mark a request's id pending/settled directly on the raw bytes as they
 * cross this bean's own streams, not on a downstream dispatch - so there is
 * no handoff duration left to guess at.
 *
 * <p>A third, later-found gap (GitHub issue #33, live-Dynatrace
 * verification, ADR 0036): {@code SimpleSpanProcessor} dispatches each
 * span's export asynchronously the instant the span ends, and nothing
 * tracked that in-flight HTTP call the way {@link InFlightRequestTracker}
 * tracks MCP responses - a real network span exporter (Dynatrace, unlike
 * the near-instant local fake server this project's test suite uses)
 * simply didn't get a chance to complete before {@code System.exit()},
 * silently dropping trace exports on almost every shutdown. Fixed with an
 * explicit, bounded {@code sdkTracerProvider.forceFlush().join(...)} in
 * {@link #awaitDrainThenShutdown}, the same pattern already proven in
 * {@code ObservabilityExportIntegrationTest}'s own test code, just not
 * previously applied to this actual production shutdown path.
 *
 * <p>A fourth gap (GitHub issue #46, 2026-09-01 master-brief gap review,
 * P2-2): a query still running when {@link InFlightRequestTracker#awaitDrain}
 * times out was previously left running regardless - either it finished on
 * its own before the JVM's {@code System.exit()} below tore down the
 * connection anyway, or it kept consuming DB2 resources for a client that
 * had already disconnected and would never read the response. {@link
 * ActiveStatementRegistry#cancelAll()} is called in that same timeout branch
 * now, proactively cancelling whatever is still running instead of leaving
 * it to an abrupt connection-drop moments later. Deliberately gated on the
 * same {@code awaitDrain} timeout that already existed for the warning log
 * above, not triggered any earlier: cancelling the instant EOF is observed
 * would reintroduce exactly the bug ADR 0031 fixed - a query a real client
 * is still waiting on, killed before it had the same grace period every
 * other in-flight request already gets.
 *
 * <p>{@code stdioServerTransport} is {@code @ConditionalOnProperty}-gated
 * off when the HTTP transport prototype is enabled (thread 6, ADR 0037):
 * {@code McpServerAutoConfiguration.mcpSyncServer(...)}/{@code
 * .mcpAsyncServer(...)} each take exactly one {@code
 * McpServerTransportProviderBase} bean, confirmed directly via {@code
 * javap} - so stdio and HTTP genuinely cannot both be active at once in
 * this MCP SDK/Spring AI version, and registering both beans
 * unconditionally would throw {@code NoUniqueBeanDefinitionException} at
 * startup the moment HTTP is enabled. {@code db2mcp.http.enabled} is a
 * genuine XOR switch between two mutually exclusive server modes, not
 * simultaneous multi-transport - see {@link
 * io.github.marekpompura.db2mcp.configuration.http.HttpTransportConfig}.
 */
@Configuration
public class StdioLifecycleConfig {

    private static final Logger log = LoggerFactory.getLogger(StdioLifecycleConfig.class);

    private final Duration shutdownGracePeriod;

    public StdioLifecycleConfig(
            @Value("${spring.lifecycle.timeout-per-shutdown-phase:10s}") Duration shutdownGracePeriod) {
        // Reuses ADR 0016's own graceful-shutdown bound rather than adding a
        // second, separately-configured timeout for the same concern: "how
        // long is this process allowed to take to shut down" already has one
        // answer in this project, whether shutdown was triggered by SIGTERM
        // or by stdin EOF.
        this.shutdownGracePeriod = shutdownGracePeriod;
    }

    @Bean
    @ConditionalOnProperty(prefix = "db2mcp.http", name = "enabled", havingValue = "false", matchIfMissing = true)
    public McpServerTransportProvider stdioServerTransport(
            ConfigurableApplicationContext context,
            InFlightRequestTracker inFlightRequestTracker,
            SdkTracerProvider sdkTracerProvider,
            ActiveStatementRegistry activeStatementRegistry) {
        InputStream trackedIn = new RequestIdTrackingInputStream(System.in, inFlightRequestTracker);
        EofTriggeredShutdownInputStream stdinWithEofShutdown = new EofTriggeredShutdownInputStream(
                trackedIn,
                () -> awaitDrainThenShutdown(context, inFlightRequestTracker, sdkTracerProvider, activeStatementRegistry));
        OutputStream trackedOut = new ResponseIdTrackingOutputStream(System.out, inFlightRequestTracker);
        // A plain JsonMapper, matching what this bean did before Spring AI
        // 2.0 (`new ObjectMapper()`) - not injecting Spring AI's own
        // autoconfigured mcpServerJsonMapper bean (McpServerJsonMapperAutoConfiguration)
        // because that bean, despite its @ConditionalOnClass/@ConditionalOnProperty/
        // @ConditionalOnMissingBean conditions all evaluating as matched in
        // the condition-evaluation report, does not actually get instantiated
        // in time for this @Bean method's parameter autowiring to find it in
        // the packaged jar's bootstrap ordering (reproduced directly against
        // target/db2-mcp-server-*.jar, not just in tests: NoSuchBeanDefinitionException
        // for tools.jackson.databind.json.JsonMapper) - a Spring AI/Spring Boot
        // autoconfiguration-ordering issue outside this project's control, not
        // something to work around by forcing a bean-creation-order dependency
        // on it. Self-contained construction sidesteps it entirely, and is
        // actually closer to this bean's original pre-migration intent (a
        // fresh, non-shared instance built here, not sourced from elsewhere)
        // than injecting a framework bean ever was. Wrapped in the SDK's
        // Jackson-backed McpJsonMapper adapter (io.modelcontextprotocol.sdk:mcp-json-jackson3,
        // already a transitive compile dependency of the SDK's mcp artifact) -
        // Spring AI 2.0 replaced the raw Jackson ObjectMapper this constructor
        // used to take with McpJsonMapper, backed by Jackson 3.x
        // (`tools.jackson.*`, a different package tree from the classic
        // Jackson 2.x `com.fasterxml.jackson.*` McpToolException still uses
        // directly elsewhere in this project).
        // SerializedStdioServerTransportProvider, not the SDK's own
        // StdioServerTransportProvider (GitHub issue #29): the SDK's
        // implementation writes outbound responses through a
        // Sinks.many().unicast() sink shared by the whole session - a
        // Reactor sink type that isn't safe for concurrent producer
        // threads, and two tool calls completing close enough in time
        // reliably lose one response to it (confirmed live, root-caused by
        // reading the SDK's own published sources, and confirmed
        // unfixable from this project's own code: neither a per-tool nor a
        // global lock around tool execution closes the race, since the
        // actual write happens in an async continuation this project has
        // no hook into). This class is a drop-in replacement - same
        // constructor shape, same wrapped-stream contract - that performs
        // the same write directly and synchronously under a lock instead,
        // with no unsynchronized enqueue step left to race on. See that
        // class's own Javadoc for the full writeup.
        return new SerializedStdioServerTransportProvider(
                new JacksonMcpJsonMapper(JsonMapper.builder().build()), stdinWithEofShutdown, trackedOut);
    }

    /**
     * Runs synchronously on the SDK's own stdin-reading thread, inside the
     * {@code read()} call that first observes EOF - see the class Javadoc
     * for why blocking here, rather than returning immediately, is what
     * actually closes the drop. Only once this method returns does {@code
     * read()} hand -1 back to the SDK.
     */
    private void awaitDrainThenShutdown(
            ConfigurableApplicationContext context,
            InFlightRequestTracker tracker,
            SdkTracerProvider sdkTracerProvider,
            ActiveStatementRegistry activeStatementRegistry) {
        if (!tracker.awaitDrain(shutdownGracePeriod)) {
            log.warn(
                    "Shutting down with {} MCP request(s) still awaiting a response after a {} grace period - their"
                            + " response(s) may not reach the client",
                    tracker.current(),
                    shutdownGracePeriod);
            // Issue #46: the client is gone and this response was never going
            // to be delivered anyway - cancel whatever DB2 statement is still
            // running rather than leave it consuming resources until it
            // finishes on its own or the JVM's System.exit() below drops the
            // connection out from under it.
            int cancelled = activeStatementRegistry.cancelAll();
            if (cancelled > 0) {
                log.info("Cancelled {} in-flight DB2 statement(s) rather than leaving them running past shutdown", cancelled);
            }
        }
        log.info("MCP client closed stdin - shutting down");
        // Off this thread from here on: SpringApplication.exit -> context.close()
        // runs DisposableBean/@PreDestroy callbacks (e.g. HikariDataSource.close()),
        // which must not happen on the very thread that's inside the read() call
        // this method was invoked from.
        Thread shutdownThread = new Thread(
                () -> {
                    // No fixed settle delay before this, unlike the count-based
                    // predecessor of this fix: tracker.awaitDrain only returns
                    // once every response *line has actually been written* to
                    // trackedOut (ResponseIdTrackingOutputStream), not merely
                    // once the tool method that produced it returned - there is
                    // nothing left to wait out here. Flushing explicitly is
                    // still worth doing, immediately, in case anything upstream
                    // buffers before reaching the OS pipe.
                    System.out.flush();
                    // Explicit, bounded force-flush of pending span exports
                    // before context.close() (GitHub issue #33, found during
                    // ADR 0036's live-Dynatrace verification): SimpleSpanProcessor
                    // dispatches each span's export asynchronously the instant
                    // the span ends, and nothing tracks that in-flight HTTP call
                    // the way InFlightRequestTracker tracks MCP responses above -
                    // without this, a real network exporter (unlike the
                    // near-instant local FakeDynatraceServer this project's own
                    // test suite uses) simply doesn't get a chance to complete
                    // before System.exit() below, silently dropping the export.
                    // Confirmed live: metrics don't have this problem
                    // (DynatraceMeterRegistry.close() is a normal, synchronously-
                    // awaited part of Spring's bean destruction), only traces do.
                    // Bounded by the same shutdownGracePeriod already used above,
                    // not a second, separately-configured timeout for the same
                    // underlying "how long is shutdown allowed to take" question.
                    sdkTracerProvider.forceFlush().join(shutdownGracePeriod.toMillis(), TimeUnit.MILLISECONDS);
                    int exitCode = SpringApplication.exit(context, () -> 0);
                    System.exit(exitCode);
                },
                "mcp-stdin-eof-shutdown");
        shutdownThread.setDaemon(false);
        shutdownThread.start();
    }
}
