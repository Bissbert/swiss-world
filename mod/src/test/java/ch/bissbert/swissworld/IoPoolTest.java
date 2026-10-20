package ch.bissbert.swissworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IoPoolTest {
    @Test
    void defaultThreadsAreHalfTheCoresBetweenTwoAndSix() {
        assertEquals(2, SwissRuntime.defaultIoThreads(1));
        assertEquals(2, SwissRuntime.defaultIoThreads(4));
        assertEquals(4, SwissRuntime.defaultIoThreads(8));
        assertEquals(6, SwissRuntime.defaultIoThreads(12));
        assertEquals(6, SwissRuntime.defaultIoThreads(64));
    }

    @Test
    void poolHasNamedDaemonThreadsAndTheRequestedSize() throws InterruptedException {
        ExecutorService pool = SwissRuntime.newIoPool(3);
        try {
            Set<String> names = ConcurrentHashMap.newKeySet();
            Set<Boolean> daemon = ConcurrentHashMap.newKeySet();
            CountDownLatch all = new CountDownLatch(3), go = new CountDownLatch(1);
            for (int i = 0; i < 3; i++) {
                pool.execute(() -> {
                    names.add(Thread.currentThread().getName());
                    daemon.add(Thread.currentThread().isDaemon());
                    all.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException ignored) {
                    }
                });
            }
            assertTrue(all.await(5, TimeUnit.SECONDS), "three tasks run at once on three threads");
            go.countDown();
            assertEquals(Set.of("swissworld-io-1", "swissworld-io-2", "swissworld-io-3"), names);
            assertEquals(Set.of(true), daemon);
        } finally {
            pool.shutdownNow();
        }
    }
}
