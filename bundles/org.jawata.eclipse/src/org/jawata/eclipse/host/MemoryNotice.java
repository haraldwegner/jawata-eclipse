package org.jawata.eclipse.host;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryNotificationInfo;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;

import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

import org.jawata.spi.host.Notice;
import org.jawata.spi.host.PersonChannel;

/**
 * The signed response to the engine sharing Eclipse's memory (plan Stage 16, step 16.5; spec risk "the engine shares Eclipse's
 * memory"): when the heap is still nearly full after a collection, the person is told once, in the background, to raise Eclipse's
 * memory. The JVM calls it after a collection, which is the only moment the heap's real occupancy is known; telling at every
 * allocation spike would tell the person about garbage.
 */
public final class MemoryNotice implements NotificationListener {

    /** What the person is told: one sentence they can act on. */
    static final String TEXT = "jawata is short of memory in this Eclipse; raise the -Xmx value in eclipse.ini and restart Eclipse.";

    private final PersonChannel person;
    private final double fraction;

    private boolean told;
    private MemoryPoolMXBean armedOn;

    /**
     * @param person   where the person is told
     * @param fraction how full the heap is after a collection before the person is told, between 0 and 1
     */
    public MemoryNotice(PersonChannel person, double fraction) {
        this.person = person;
        this.fraction = fraction;
    }

    /** Whether a heap that holds {@code used} of {@code max} bytes after a collection is full enough to tell the person. */
    static boolean pressed(long used, long max, double fraction) {
        return max > 0 && used >= (long) (max * fraction);
    }

    /**
     * Looks at the heap's occupancy after a collection and tells the person the first time it is too full; never again, because
     * a person who has been told and has not restarted Eclipse must not be told on every collection.
     *
     * @param usedAfterCollection the old-generation bytes still in use after a collection
     * @param max                 the old generation's largest size
     */
    public synchronized void check(long usedAfterCollection, long max) {
        if (!told && pressed(usedAfterCollection, max, fraction)) {
            told = true;
            person.tell(Notice.warning(TEXT));
        }
    }

    /** Asks the JVM to call this after any collection that leaves the old generation fuller than the fraction. */
    public synchronized void arm() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.isCollectionUsageThresholdSupported() && pool.getUsage().getMax() > 0) {
                pool.setCollectionUsageThreshold((long) (pool.getUsage().getMax() * fraction));
                armedOn = pool;
            }
        }
        ((NotificationEmitter) ManagementFactory.getMemoryMXBean()).addNotificationListener(this, null, null);
    }

    /** Stops the JVM calling this, and clears the threshold it set. */
    public synchronized void disarm() {
        try {
            ((NotificationEmitter) ManagementFactory.getMemoryMXBean()).removeNotificationListener(this);
        } catch (ListenerNotFoundException notArmed) {
            // never armed: nothing to remove
        }
        if (armedOn != null) {
            armedOn.setCollectionUsageThreshold(0);
            armedOn = null;
        }
    }

    @Override
    public void handleNotification(Notification notification, Object handback) {
        if (MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED.equals(notification.getType())) {
            MemoryNotificationInfo info = MemoryNotificationInfo.from((CompositeData) notification.getUserData());
            check(info.getUsage().getUsed(), info.getUsage().getMax());
        }
    }
}
