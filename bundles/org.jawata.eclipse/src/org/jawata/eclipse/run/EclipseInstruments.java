package org.jawata.eclipse.run;

import java.util.Optional;

import org.jawata.spi.host.DebugTurn;
import org.jawata.spi.host.Instruments;

/**
 * What Eclipse tells the engine about debugging (plan Stages 19 and 25a; contract §4.2): whose turn it is to debug, and how to run the
 * person's configurations for jawata. The second is what makes the engine's {@code debug launch configuration=} and the discovery label
 * "started by the person for jawata" work inside Eclipse; a host without it answers nothing and the agent starts programs itself.
 */
public final class EclipseInstruments implements Instruments {

    private final DebugTurn turn;
    private final org.jawata.spi.host.HostRuns runs;

    /**
     * @param turn whose turn it is to debug
     * @param runs how programs are run for jawata from the person's run configurations
     */
    public EclipseInstruments(DebugTurn turn, org.jawata.spi.host.HostRuns runs) {
        this.turn = turn;
        this.runs = runs;
    }

    @Override
    public DebugTurn debugTurn() {
        return turn;
    }

    @Override
    public Optional<org.jawata.spi.host.HostRuns> runs() {
        return Optional.of(runs);
    }
}
