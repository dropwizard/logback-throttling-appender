package io.dropwizard.logback;

import ch.qos.logback.core.AppenderBase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public class CollectingAppender<E> extends AppenderBase<E> {
    private final ConcurrentLinkedQueue<E> events = new ConcurrentLinkedQueue<>();

    @Override
    protected void append(E eventObject) {
        events.add(eventObject);
    }

    public List<E> getEvents() {
        return new ArrayList<>(events);
    }
}
