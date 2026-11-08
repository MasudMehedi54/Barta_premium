package com.barta.app.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One shared background thread pool for disk, image and network work. */
public final class AppExecutors {
    public static final ExecutorService IO = Executors.newFixedThreadPool(3);

    private AppExecutors() { }
}
