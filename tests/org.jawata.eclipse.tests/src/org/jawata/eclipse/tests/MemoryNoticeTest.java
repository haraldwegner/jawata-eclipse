package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.ArrayList;
import java.util.List;

import org.jawata.eclipse.host.MemoryNotice;
import org.jawata.spi.host.Notice;
import org.jawata.spi.host.PersonChannel;
import org.junit.jupiter.api.Test;

/**
 * Plan Stage 16, step 16.5: the signed response to the engine sharing Eclipse's memory. The person is told once, in the words they
 * can act on, when the heap is nearly full after a collection, and never when it is not; and arming it makes the JVM call it.
 */
public class MemoryNoticeTest {

    private static final class Recording implements PersonChannel {
        final List<Notice> told = new ArrayList<>();

        @Override
        public void tell(Notice notice) {
            told.add(notice);
        }

        @Override
        public Activity begin(String label) {
            throw new UnsupportedOperationException("the memory notice only tells");
        }
    }

    @Test
    public void theNoticeIsToldOnceWhenTheHeapIsNearlyFull() {
        Recording person = new Recording();
        MemoryNotice notice = new MemoryNotice(person, 0.9);

        notice.check(500, 1000);
        assertTrue(person.told.isEmpty(), "a heap half full is not told");

        notice.check(950, 1000);
        notice.check(990, 1000);

        assertEquals(1, person.told.size(), "told once, not at every collection: " + person.told);
        assertEquals(Notice.Level.WARNING, person.told.get(0).level());
        assertTrue(person.told.get(0).text().contains("-Xmx"), "and the sentence names the setting to raise: " + person.told.get(0).text());
    }

    @Test
    public void theJvmIsAskedToCallItAfterACollection() {
        MemoryNotice notice = new MemoryNotice(new Recording(), 0.9);
        try {
            notice.arm();
            assertTrue(ManagementFactory.getMemoryPoolMXBeans().stream()
                .anyMatch(pool -> pool.getType() == MemoryType.HEAP && pool.isCollectionUsageThresholdSupported()
                    && pool.getCollectionUsageThreshold() > 0), "a heap pool now has a collection threshold");
        } finally {
            notice.disarm();
        }
        assertTrue(ManagementFactory.getMemoryPoolMXBeans().stream().filter(MemoryPoolMXBean::isCollectionUsageThresholdSupported)
            .allMatch(pool -> pool.getCollectionUsageThreshold() == 0), "and disarming clears it");
    }
}
