package node;

import communication.NeighborHealth;
import communication.contractnet.ContractNetNodeClient;
import communication.handoff.InterventionCompleted;
import communication.handoff.NodeClient;
import domain.vehicle.Vehicle;
import domain.vehicle.VehicleStatus;
import domain.zone.Zone;
import domain.zone.ZoneId;
import io.vertx.core.Vertx;
import node.config.NodeConfig;
import node.server.NodeServer;
import node.topology.ZoneGridFactory;
import node.vehicle.VehicleLifecycleManager;
import node.vehicle.VehiclePopulator;
import node.zone.*;

import java.nio.file.Path;
import java.util.Map;

// crea una zona
public class Main {

    public static void main(String[] args) {
        NodeConfig config = NodeConfig.fromEnvironment(); // legge i parametri della zona da creare dalle variabili d'ambiente Docker (proprie del container avviato)
        ZoneId myZoneId = ZoneGridFactory.zoneIdOf(config.zoneRow(), config.zoneCol()); // definisce l'id della zona
        Map<ZoneId, Zone> grid = ZoneGridFactory.buildGrid(config.gridRows(), config.gridCols(), config.mapLatMin(), config.mapLatMax(), config.mapLonMin(), config.mapLonMax()); // costruisce la griglia
        Zone myZone = grid.get(myZoneId); // crea la zona (cercandola per id nella mappa restituita dalla creazione della griglia)
        if (myZone == null) {
            throw new IllegalStateException("Zone not found in computed grid: " + myZoneId);
        }
        SqliteDatabase database = new SqliteDatabase(config.dbPath());
        LoanRepository loanRepository = new LoanRepository(database); // inizializza il repository dei prestiti cross-zona
        VehicleRepository vehicleRepository = new VehicleRepository(database);
        EmergencyRepository emergencyRepository = new EmergencyRepository(database);
        ZoneState zoneState = new ZoneState(myZone, database, loanRepository, vehicleRepository, emergencyRepository); //inizializza lo stato della zona
        VehiclePopulator.populate(zoneState, Path.of(config.vehiclesConfigPath())); // popola i veicoli iniziali della zona
        zoneState.restoreEmergencies(); // ricarica dal db le emergenze aperte (è fondamentale che sia qui e non nel verticle perche è un'operazione bloccante dato che accede al db, e l'event loop non dovrebbe mai bloccarsi)

        Vertx vertx = Vertx.vertx();

        NeighborHealth neighborHealth = new NeighborHealth(myZone.getNeighbors());
        NodeClient nodeClient = new NodeClient(vertx, config::addressFor, neighborHealth, config.neighborTimeoutMs(), config.handoffRetryIntervalMs());
        VehicleLifecycleManager lifecycleManager = new VehicleLifecycleManager(zoneState, vertx, vehicleRepository, nodeClient);
        vertx.deployVerticle(new NodeServer(config.httpPort(), zoneState, config.reservationTimeoutMs(), vehicleRepository, lifecycleManager)) // avvia il server http
                .onSuccess(deploymentId -> { // se ha avuto successo
                    System.out.println("Node started for zone " + myZoneId.value() + " (row=" + config.zoneRow() + ", col=" + config.zoneCol() + ")" + " listening on port " + config.httpPort());
                    ContractNetNodeClient contractNetClient = new ContractNetNodeClient(vertx, config::addressFor, neighborHealth, config.neighborTimeoutMs());
                    // TODO: usare contractNetClient.callForProposals(...) nella logica di gestione emergenze quando un'emergenza resta senza veicolo locale disponibile
                    for (PendingLoan loan : zoneState.loadPendingLoans()) { // ogni volta che il nodo si riavvia dopo il crash, recupera dal db i prestiti in sospeso
                        InterventionCompleted completionMessage = new InterventionCompleted(loan.emergencyId(), loan.vehicleId(), loan.category());
                        nodeClient.notifyCompletion(loan.requesterZoneId(), completionMessage, () -> zoneState.deletePendingLoan(loan.vehicleId(), loan.emergencyId())); // notifica all'initiator il completamento dell'intervento del veicolo sull'emergenza
                    }

                    long now = System.currentTimeMillis(); // orario attuale

                    // ripristina le informazioni dei veicoli

                    for (VehicleCheckpoint checkpoint : vehicleRepository.loadAll()) { // recupera dal db l'ultimo stato noto dei veicoli non AVAILABLE al momento del crash e li ricostruisce
                        Vehicle vehicle = zoneState.getVehicle(checkpoint.vehicleId());
                        vehicle.setStatus(checkpoint.status());
                        vehicle.setPosition(checkpoint.currentPosition());
                        vehicle.setDestination(checkpoint.destination());
                        vehicle.setBatteryLevel(checkpoint.batteryLevel());
                        if (checkpoint.emergencyId() != null) { // se era un prestito attivo, lo riaggiunge ai prestiti attivi
                            zoneState.addActiveLoan(checkpoint.vehicleId(), checkpoint.emergencyId(), checkpoint.requesterZoneId(), vehicle.getCategory());
                        }

                        // si coordina sulla fine dell'intervento

                        long referenceTime = checkpoint.checkpointAtMillis(); // prende l'orario dell'ultimo salvataggio del checkpoint
                        if (checkpoint.status() == VehicleStatus.ON_SCENE && checkpoint.onSceneStartedAtMillis() != null) { // se il veicolo era sull'emergenza e quindi stava iniziando l'intervento
                            /*
                            registra il momento di inizio intervento del veicolo sull'emergenza, per non perderlo nel caso nel caso in cui l'intervento non si è concluso e si verifichi un secondo crash
                            (senza questo salvataggio, la mappa che viene utilizzata in checkpointAllVehicles per il salvataggio del checkpoint sarebbe vuota - il nodo è stato resettato dopo il riavvio dal crash - e quindi non saprebbe da quale momento l'intervento del veicolo è iniziato)
                             */
                            lifecycleManager.registerOnSceneStart(checkpoint.vehicleId(), checkpoint.onSceneStartedAtMillis());
                            if (checkpoint.onSceneDeadlineMillis() != null) {
                                lifecycleManager.registerOnSceneDeadline(checkpoint.vehicleId(), checkpoint.onSceneDeadlineMillis()); // registra il momento di fine intervento effettivo, per non perderlo nel caso nel caso in cui l'intervento non si è concluso e si verifichi un secondo crash
                            }
                            referenceTime = checkpoint.onSceneStartedAtMillis(); // prende il tempo dall'inizio reale dell'intervento sull'emergenza
                        }
                        long elapsedMs = now - referenceTime; // tempo trascorso durante il crash
                        lifecycleManager.recoverVehicle(vehicle, elapsedMs); // ricostruisce il comportamento del veicolo durante il crash
                    }
                    lifecycleManager.start(); // avvia il ciclo di vita dei veicoli
                    lifecycleManager.startCheckpointing(); // avvia il salvataggio periodico dei checkpoint dei veicoli
                })
                .onFailure(err -> { // se ha fallito
                    System.err.println("Node failed to start: " + err.getMessage());
                    System.exit(1); // termina il thread vert
                });
    }
}