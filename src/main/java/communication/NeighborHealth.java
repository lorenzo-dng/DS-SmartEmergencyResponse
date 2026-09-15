package communication;

import domain.zone.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// stato di raggiungibilità dei nodi vicini
public class NeighborHealth {

    private final Map<ZoneId, Boolean> reachable;

    public NeighborHealth(Set<ZoneId> neighbors) {
        this.reachable = new HashMap<>();
        neighbors.forEach(zoneId -> reachable.put(zoneId, true)); // considera di default che i nodi vicini sono raggiungibili
    }

    public void markUnreachable(ZoneId zoneId) {
        reachable.put(zoneId, false);
    }

    public void markReachable(ZoneId zoneId) {
        reachable.put(zoneId, true);
    }

    public boolean isReachable(ZoneId zoneId) {
        return reachable.getOrDefault(zoneId, true);
    }
}