package io.dropwizard.logback;

import ch.qos.logback.core.AsyncAppenderBase;
import ch.qos.logback.core.spi.DeferredProcessingAware;

public interface AsyncAppenderBaseProxy<E extends DeferredProcessingAware> {
    AsyncAppenderBase<E> getAppender();
}
