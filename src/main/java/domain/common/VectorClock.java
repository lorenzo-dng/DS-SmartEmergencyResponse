package domain.common;

import domain.zone.ZoneId;
import java.util.*;

public class VectorClock {

    private final Map<ZoneId, Integer> clock; //vector clock (zona - intero)

    //costruttore per la prima creazione del nodo (da zero)
    public VectorClock() {
        this.clock = new HashMap<>();
    }

    //costruttore per ripristino del nodo dopo crash
    public VectorClock(Map<ZoneId, Integer> initial) {
        this.clock = new HashMap<>(initial);
    }

    //incrementa il vector clock quando il nodo esegue un'azione
    public VectorClock increment(ZoneId zoneId) {
        Map<ZoneId, Integer> updated = new HashMap<>(clock); //crea una copia del vector clock
        updated.merge(zoneId, 1, Integer::sum); //incrementa il valore associato a zoneId
        return new VectorClock(updated);
    }

    //unisce il vector clock locale con quello ricevuto da un altro nodo
    public VectorClock merge(VectorClock other) {
        Map<ZoneId, Integer> updated = new HashMap<>(this.clock); //crea una copia del vector clock
        for (Map.Entry<ZoneId, Integer> e : other.clock.entrySet()) { // per ogni zona
            updated.merge(e.getKey(), e.getValue(), Math::max); //sceglie il massimo intero tra il clock locale e quello ricevuto
        }
        return new VectorClock(updated);
    }

    //confronta due vector clock
    public VectorClockComparison compare(VectorClock other) {
        //crea un Set contenente tutte le chiavi (ZoneId) presenti nel vector clock corrente
        Set<ZoneId> keys = new HashSet<>(this.clock.keySet());
        keys.addAll(other.clock.keySet());

        boolean greater = false;
        boolean less = false;

        for (ZoneId key : keys) { //per tutte le zone
            int v1 = this.clock.getOrDefault(key, 0); //recupera dal vector clock corrente il valore associato alla zona. Se non esiste, restituisce 0
            int v2 = other.clock.getOrDefault(key, 0); //recupera dal vector other il valore associato alla zona. Se non esiste, restituisce 0
            if (v1 > v2) greater = true; //se il valore corrente è maggiore, imposta greater a true
            if (v1 < v2) less = true; //se il valore corrente è minore, imposta less a true
        }

        if (greater && less) return VectorClockComparison.CONCURRENT; //se il vector clock corrente è maggiore in almeno una componente e minore in almeno un'altra, i due vector clock sono concorrenti.
        if (greater) return VectorClockComparison.AFTER; //se il vector clock corrente è solo maggiore dell'altro, è successivo all'altro.
        if (less) return VectorClockComparison.BEFORE; //se il vector clock corrente è solo minore dell'altro, è precedente all'altro.
        return VectorClockComparison.EQUAL; //altrimenti, i due vector clock sono uguali
    }

    //restituisce una vista di sola lettura del clock
    public Map<ZoneId, Integer> snapshot() {
        return Collections.unmodifiableMap(clock);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VectorClock that)) return false;
        return Objects.equals(clock, that.clock);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clock);
    }

    @Override
    public String toString() {
        return clock.toString();
    }
}