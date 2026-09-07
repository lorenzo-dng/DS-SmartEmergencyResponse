package domain.zone;

import domain.common.Position;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class Zone {
    private final ZoneId id;
    private final double latMin, latMax; // confine minimo, massimo di latitudine di questa zona (nord-sud)
    private final double lonMin, lonMax; // confine minimo, massimo di longitude di questa zona (est-ovest)
    private final boolean isTopRow;      // serve per decidere che una posizione sul bordo superiore deve ricadere nella zona attuale quando non ce ne sono altre sopra (normalmente apparterrebbe alla zona sotto)
    private final boolean isRightCol;    // serve per decidere che una posizione sul bordo destro deve ricadere nella zona attuale quando non ce ne sono altre a destra (normalmente apparterrebbe alla zona a sinistra)
    private final Set<ZoneId> neighbors; // lista delle zone vicine

    public Zone(ZoneId id, double latMin, double latMax, double lonMin, double lonMax, boolean isTopRow, boolean isRightCol, Set<ZoneId> neighbors) {
        if (id == null) {
            throw new IllegalArgumentException("ZoneId cannot be null");
        }
        if (latMin >= latMax) {
            throw new IllegalArgumentException("latMin must be < latMax");
        }
        if (lonMin >= lonMax) {
            throw new IllegalArgumentException("lonMin must be < lonMax");
        }
        if (neighbors == null) {
            throw new IllegalArgumentException("neighbors cannot be null");
        }
        this.id = id;
        this.latMin = latMin;
        this.latMax = latMax;
        this.lonMin = lonMin;
        this.lonMax = lonMax;
        this.isTopRow = isTopRow;
        this.isRightCol = isRightCol;
        this.neighbors = new HashSet<>(neighbors);
    }

    public ZoneId getId() {
        return id;
    }

    // restituisce una vista di sola lettura delle zone vicine (pensato per fare in modo che nessuno dall'esterno possa aggiungere o rimuovere una zona vicina
    // (non dovrebbe mai accadere perche la griglia viene calcolata una sola volta e non puo cambiare)
    public Set<ZoneId> getNeighbors() {
        return Collections.unmodifiableSet(neighbors);
    }

    //verifica se una posizione (come un emergenza) è presente nella zona
    public boolean contains(Position position) {
        // la cella della griglia è posta in alto a tutto? se si, si considera il confine superiore appartenente a questa cella, altrimenti si considera il confine superiore appartenente alla cella in basso
        boolean latOk = isTopRow ? position.latitude() >= latMin && position.latitude() <= latMax : position.latitude() >= latMin && position.latitude() < latMax;

        // la cella della griglia è posta a destra a tutto? se si, si considera il confine destro appartenente a questa cella, altrimenti si considera il confine destro appartenente alla cella a sinistra
        boolean lonOk = isRightCol ? position.longitude() >= lonMin && position.longitude() <= lonMax : position.longitude() >= lonMin && position.longitude() < lonMax;
        return latOk && lonOk;
    }
}