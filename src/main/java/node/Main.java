package node;

import domain.zone.Zone;
import domain.zone.ZoneId;
import node.config.NodeConfig;
import node.topology.ZoneGridFactory;
import node.zone.ZoneState;
import java.util.Map;

// crea una zona
public class Main {

    public static void main(String[] args) {
        NodeConfig config = NodeConfig.fromEnvironment(); // legge i parametri della zona da creare dalle variabili d'ambiente Docker (proprie del container avviato)
        ZoneId myZoneId = ZoneGridFactory.zoneIdOf(config.zoneRow(), config.zoneCol()); // definisce l'id della zona
        Map<ZoneId, Zone> grid = ZoneGridFactory.buildGrid(config.gridRows(), config.gridCols(), config.mapLatMin(), config.mapLatMax(), config.mapLonMin(), config.mapLonMax()); // costruisce la griglia
        Zone myZone = grid.get(myZoneId);
        if (myZone == null) {
            throw new IllegalStateException("Zone not found in computed grid: " + myZoneId);
        }

        ZoneState zoneState = new ZoneState(myZone); //inizializza lo stato della zona

        System.out.println("Node started for zone " + myZoneId.value() + " (row=" + config.zoneRow() + ", col=" + config.zoneCol() + ")" + " listening on port " + config.httpPort());

        // TODO: popolare i veicoli iniziali di questa zona in zoneState
        // TODO: avviare il server HTTP (Vert.x) per ricevere richieste da GUI e altri nodi
        // TODO: avviare il meccanismo di crash detection verso i vicini
    }
}