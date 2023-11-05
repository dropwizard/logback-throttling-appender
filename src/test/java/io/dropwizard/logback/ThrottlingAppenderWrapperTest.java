package io.dropwizard.logback;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.filter.LevelFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.spi.FilterReply;
import ch.qos.logback.core.spi.LifeCycle;
import ch.qos.logback.core.status.ErrorStatus;
import ch.qos.logback.core.status.InfoStatus;
import ch.qos.logback.core.status.WarnStatus;
import com.google.common.util.concurrent.RateLimiter;
import org.assertj.core.api.Condition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThrottlingAppenderWrapperTest {
    private static final int WRITE_LINES = 100;
    private static final long RUN_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1L);

    private static final String APP_LOG_PREFIX = "Application log";
    private static final Condition<ILoggingEvent> APP_LOG_CONDITION = new Condition<>(o -> o.getMessage().contains(APP_LOG_PREFIX), "contains application log");

    private LoggerContext context;
    private CollectingAppender<ILoggingEvent> collectingAppender;
    private AsyncAppender asyncAppender;

    @BeforeEach
    void setup() {
        context = new LoggerContext();
        context.setName("context-test");
        context.setMDCAdapter(new LogbackMDCAdapter());
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
    @SuppressWarnings("NullAway")
    void nullAppender() {
        assertThatThrownBy(() -> new ThrottlingAppenderWrapper<>(null, 1L, TimeUnit.SECONDS))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("delegate must not be null!");
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
            final LoggingEvent event = newLoggingEvent();
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

    @Test
    void testGetAppender() {
        final ThrottlingAppenderWrapper<ILoggingEvent> wrapper = new ThrottlingAppenderWrapper<>(asyncAppender, 1L, TimeUnit.SECONDS);
        assertThat(wrapper.getAppender()).isEqualTo(asyncAppender);
    }

    @Test
    void testLifeCycle() {
        final AsyncAppender delegate = new AsyncAppender();
        delegate.setContext(context);
        delegate.addAppender(collectingAppender);

        final ThrottlingAppenderWrapper<ILoggingEvent> wrapper = new ThrottlingAppenderWrapper<>(delegate, 1L, TimeUnit.SECONDS);
        assertThat(delegate.isStarted()).isFalse();
        assertThat(wrapper.isStarted()).isFalse();

        wrapper.start();
        assertThat(delegate.isStarted()).isTrue();
        assertThat(wrapper.isStarted()).isTrue();

        wrapper.stop();
        assertThat(delegate.isStarted()).isFalse();
        assertThat(wrapper.isStarted()).isFalse();
    }

    @Test
    void testDoAppend() {
        final ThrottlingAppenderWrapper<ILoggingEvent> wrapper = new ThrottlingAppenderWrapper<>(asyncAppender, 1L, TimeUnit.SECONDS);
        wrapper.start();
        final LoggingEvent event = newLoggingEvent();
        wrapper.doAppend(event);
        wrapper.stop();

        assertThat(collectingAppender.getEvents()).containsOnly(event);
    }

    @Test
    void testName() {
        final ThrottlingAppenderWrapper<ILoggingEvent> wrapper = new ThrottlingAppenderWrapper<>(asyncAppender, 1L, TimeUnit.SECONDS);
        wrapper.setName("test");
        assertThat(wrapper.getName()).isEqualTo("test");
    }

    @Test
    void testContext() {
        final ThrottlingAppenderWrapper<ILoggingEvent> wrapper = new ThrottlingAppenderWrapper<>(asyncAppender, 1L, TimeUnit.SECONDS);
        wrapper.setContext(context);
        assertThat(wrapper.getContext()).isEqualTo(context);
    }

    @Test
    void testAddStatus() {
        final ThrottlingAppenderWrapper<ILoggingEvent> wrapper = new ThrottlingAppenderWrapper<>(asyncAppender, 1L, TimeUnit.SECONDS);
        wrapper.setContext(context);
        final InfoStatus status = new InfoStatus("Info", wrapper);
        wrapper.addStatus(status);
        assertThat(context.getStatusManager().getCopyOfStatusList()).contains(status);
    }

    @Test
    void testAddInfo() {
        final ThrottlingAppenderWrapper<ILoggingEvent> wrapper = new ThrottlingAppenderWrapper<>(asyncAppender, 1L, TimeUnit.SECONDS);
        wrapper.setContext(context);
        final InfoStatus status = new InfoStatus("Info", wrapper);
        wrapper.addInfo("Info");
        assertThat(context.getStatusManager().getCopyOfStatusList()).contains(status);
    }

    @Test
    void testAddInfoWithThrowable() {
        final ThrottlingAppenderWrapper<ILoggingEvent> wrapper = new ThrottlingAppenderWrapper<>(asyncAppender, 1L, TimeUnit.SECONDS);
        wrapper.setContext(context);
        final InfoStatus status = new InfoStatus("Info", wrapper);
        final Exception ex = new Exception();
        wrapper.addInfo("Info", ex);
        assertThat(context.getStatusManager().getCopyOfStatusList()).contains(status);
    }

    @Test
    void testAddWarn() {
        final ThrottlingAppenderWrapper<ILoggingEvent> wrapper = new ThrottlingAppenderWrapper<>(asyncAppender, 1L, TimeUnit.SECONDS);
        wrapper.setContext(context);
        final WarnStatus status = new WarnStatus("Warn", wrapper);
        wrapper.addWarn("Warn");
        assertThat(context.getStatusManager().getCopyOfStatusList()).contains(status);
    }

    @Test
    void testAddWarnWithThrowable() {
        final ThrottlingAppenderWrapper<ILoggingEvent> wrapper = new ThrottlingAppenderWrapper<>(asyncAppender, 1L, TimeUnit.SECONDS);
        wrapper.setContext(context);
        final WarnStatus status = new WarnStatus("Warn", wrapper);
        final Exception ex = new Exception();
        wrapper.addWarn("Warn", ex);
        assertThat(context.getStatusManager().getCopyOfStatusList()).contains(status);
    }

    @Test
    void testAddError() {
        final ThrottlingAppenderWrapper<ILoggingEvent> wrapper = new ThrottlingAppenderWrapper<>(asyncAppender, 1L, TimeUnit.SECONDS);
        wrapper.setContext(context);
        final ErrorStatus status = new ErrorStatus("Error", wrapper);
        wrapper.addError("Error");
        assertThat(context.getStatusManager().getCopyOfStatusList()).contains(status);
    }

    @Test
    void testAddErrorWithThrowable() {
        final ThrottlingAppenderWrapper<ILoggingEvent> wrapper = new ThrottlingAppenderWrapper<>(asyncAppender, 1L, TimeUnit.SECONDS);
        wrapper.setContext(context);
        final ErrorStatus status = new ErrorStatus("Error", wrapper);
        final Exception ex = new Exception();
        wrapper.addError("Error", ex);
        assertThat(context.getStatusManager().getCopyOfStatusList()).contains(status);
    }


    @Test
    void testClearAllFilters() {
        final ThrottlingAppenderWrapper<ILoggingEvent> wrapper = new ThrottlingAppenderWrapper<>(asyncAppender, 1L, TimeUnit.SECONDS);
        final LevelFilter filter = new LevelFilter();
        wrapper.addFilter(filter);
        assertThat(wrapper.getCopyOfAttachedFiltersList()).containsOnly(filter);
        wrapper.clearAllFilters();
        assertThat(wrapper.getCopyOfAttachedFiltersList()).isEmpty();
    }

    @Test
    void testGetCopyOfAttachedFiltersList() {
        final ThrottlingAppenderWrapper<ILoggingEvent> wrapper = new ThrottlingAppenderWrapper<>(asyncAppender, 1L, TimeUnit.SECONDS);
        final LevelFilter filter = new LevelFilter();
        wrapper.addFilter(filter);
        assertThat(wrapper.getCopyOfAttachedFiltersList()).containsOnly(filter);
    }

    @Test
    void testGetFilterChainDecision() {
        final ThrottlingAppenderWrapper<ILoggingEvent> wrapper = new ThrottlingAppenderWrapper<>(asyncAppender, 1L, TimeUnit.SECONDS);
        final LevelFilter filter = new LevelFilter();
        filter.setLevel(Level.ERROR);
        filter.setOnMismatch(FilterReply.DENY);
        filter.start();
        wrapper.addFilter(filter);
        wrapper.start();

        final LoggingEvent event = newLoggingEvent();
        event.setLevel(Level.DEBUG);

        assertThat(wrapper.getFilterChainDecision(event)).isEqualTo(FilterReply.DENY);
    }

    @Test
    void testToString() {
        final ThrottlingAppenderWrapper<ILoggingEvent> wrapper = new ThrottlingAppenderWrapper<>(asyncAppender, 1L, TimeUnit.SECONDS);
        assertThat(wrapper.toString()).isEqualTo("io.dropwizard.logback.ThrottlingAppenderWrapper[async-appender-test]");
    }

    private LoggingEvent newLoggingEvent() {
        final LoggingEvent event = new LoggingEvent();
        event.setLoggerContext(context);
        return event;
    }
}
