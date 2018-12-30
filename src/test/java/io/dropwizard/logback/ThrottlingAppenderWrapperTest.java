package io.dropwizard.logback;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.spi.LifeCycle;
import com.google.common.util.concurrent.RateLimiter;
import org.assertj.core.api.Condition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ThrottlingAppenderWrapperTest {
    // Test defaults:
    private static final int WRITE_LINES = 100;
    private static final long RUN_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1L);

    // Test helpers:
    private static final String APP_LOG_PREFIX = "Application log";
    private static final Condition<ILoggingEvent> APP_LOG_CONDITION = new Condition<>(o -> o.getMessage().contains(APP_LOG_PREFIX), "contains application log");

    private LoggerContext context;
    private CollectingAppender<ILoggingEvent> collectingAppender;
    private AsyncAppender asyncAppender;

    @BeforeEach
    void setup() {
        context = new LoggerContext();
        context.setName("context-test");
        context.start();

        collectingAppender = new CollectingAppender<>();
        collectingAppender.setName("appender-test");
        collectingAppender.setContext(context);
        collectingAppender.start();

        asyncAppender = new AsyncAppender();
        asyncAppender.setName("async-appender-test");
        asyncAppender.setContext(context);
        asyncAppender.addAppender(collectingAppender);
        asyncAppender.start();
    }

    @AfterEach
    void teardown() {
        stopLifeCycleAwareObject(asyncAppender);
        stopLifeCycleAwareObject(collectingAppender);
        stopLifeCycleAwareObject(context);
    }

    private void stopLifeCycleAwareObject(LifeCycle o) {
        if (o != null && o.isStarted()) {
            o.stop();
        }
    }

    @Test
    void appenderWithZeroMessageRate() {
        assertThatThrownBy(() -> new ThrottlingAppenderWrapper<>(asyncAppender, 0L, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageRate must be positive!");
    }

    @Test
    void appenderWithNegativeMessageRate() {
        assertThatThrownBy(() -> new ThrottlingAppenderWrapper<>(asyncAppender, -1L, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageRate must be positive!");
    }

    @Test
    void overThrottlingLimit() throws Exception {
        this.runLineTest(TimeUnit.MILLISECONDS.toNanos(100L), WRITE_LINES, RUN_WINDOW_NANOS);
    }

    @Test
    void belowThrottlingLimit() throws Exception {
        this.runLineTest(TimeUnit.MICROSECONDS.toNanos(1L), WRITE_LINES, RUN_WINDOW_NANOS);
    }

    private void runLineTest(final long messageRateNanos, final int lineCount, final double runWindowNanos) throws Exception {
        final ThrottlingAppenderWrapper<ILoggingEvent> throttlingAppenderWrapper = new ThrottlingAppenderWrapper<>(asyncAppender, messageRateNanos, TimeUnit.NANOSECONDS);

        final long start = System.nanoTime();
        final double seconds = runWindowNanos / TimeUnit.SECONDS.toNanos(1L);

        // We need to subtract 1 as we automatically get one at time zero; how
        // long should the remaining take?
        final double limit = (lineCount - 1) / seconds;

        final RateLimiter rateLimiter = RateLimiter.create(limit);
        for (int i = 0; i < lineCount; i++) {
            rateLimiter.acquire();
            final LoggingEvent event = new LoggingEvent();
            event.setLevel(Level.INFO);
            event.setLoggerName("test");
            event.setMessage(APP_LOG_PREFIX + " " + i);

            throttlingAppenderWrapper.doAppend(event);
        }

        final long elapsed = System.nanoTime() - start;

        // Force logger to flush
        stopLifeCycleAwareObject(asyncAppender);
        stopLifeCycleAwareObject(collectingAppender);
        stopLifeCycleAwareObject(context);

        // Note that when we write slower than our threshold, more intervals
        // will elapse than we have lines, thus we need a Math.min guard rail so
        // our tests function.
        final double messagesPerWindow = runWindowNanos / messageRateNanos;

        // We add 1 as we get an automatic message at time zero
        final int low = Math.min((int) messagesPerWindow, lineCount);

        // How many durations did we cover (i.e how many log lines do we expect
        // worst case host performance)
        final double intervals = (double) elapsed / (double) messageRateNanos;

        // If we had a fractional interval, round up instead of down. We have to
        // add one regardless as at full speed, we end up with 1 + executed
        // intervals as an event fires at time zero.
        int high = 1 + (int) Math.ceil(intervals);
        high = Math.min(high, lineCount);

        assertThat(collectingAppender.getEvents())
                .doesNotHaveDuplicates()
                .haveAtLeast(low, APP_LOG_CONDITION)
                .haveAtMost(high, APP_LOG_CONDITION);
    }
}
