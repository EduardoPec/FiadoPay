package edu.ucsal.fiadopay.concurrent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class WorkerPool {
    private final ExecutorService async = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

    public ExecutorService async() { return async; }
    public ScheduledExecutorService scheduler() { return scheduler; }

    public void shutdown() {
        async.shutdown();
        scheduler.shutdown();
    }
}
