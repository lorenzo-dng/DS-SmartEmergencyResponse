package node;

import communication.NeighborHealth;
import communication.contractnet.ContractNetNodeClient;
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
        LoanRepository loanRepository = new LoanRepository(config.pendingLoansDbPath()); // inizializza il repository dei prestiti cross-zona
        CheckpointRepository checkpointRepository = new CheckpointRepository(config.pendingLoansDbPath()); // inizializza il repository dei checkpoint dei veicoli
        ZoneState zoneState = new ZoneState(myZone, loanRepository, checkpointRepository); //inizializza lo stato della zona
        VehiclePopulator.populate(zoneState, Path.of(config.vehiclesConfigPath())); // popola i veicoli iniziali della zona

        Vertx vertx = Vertx.vertx();
        InterventionNotifier interventionNotifier = new InterventionNotifier(vertx, config::addressFor, zoneState, config.neighborTimeoutMs(), config.handoffRetryIntervalMs());
        ArrivalNotifier arrivalNotifier = new ArrivalNotifier(vertx, config::addressFor, config.neighborTimeoutMs(), config.handoffRetryIntervalMs());
        SceneDeadlineNotifier sceneDeadlineNotifier = new SceneDeadlineNotifier(vertx, config::addressFor, config.neighborTimeoutMs(), config.handoffRetryIntervalMs());
        VehicleLifecycleManager lifecycleManager = new VehicleLifecycleManager(zoneState, vertx, interventionNotifier, checkpointRepository, arrivalNotifier, sceneDeadlineNotifier);
        vertx.deployVerticle(new NodeServer(config.httpPort(), zoneState, config.reservationTimeoutMs(), checkpointRepository, lifecycleManager)) // avvia il server http
                .onSuccess(deploymentId -> { // se ha avuto successo
                    System.out.println("Node started for zone " + myZoneId.value() + " (row=" + config.zoneRow() + ", col=" + config.zoneCol() + ")" + " listening on port " + config.httpPort());
                    NeighborHealth neighborHealth = new NeighborHealth(myZone.getNeighbors());
                    ContractNetNodeClient contractNetClient = new ContractNetNodeClient(vertx, config::addressFor, neighborHealth, config.neighborTimeoutMs());
                    // TODO: usare contractNetClient.callForProposals(...) nella logica di gestione emergenze quando un'emergenza resta senza veicolo locale disponibile
                    for (PendingLoan loan : zoneState.loadPendingLoans()) { // ogni volta che il nodo si riavvia dopo il crash, recupera dal db i prestiti in sospeso
                        interventionNotifier.notifyCompletion(loan.emergencyId(), loan.requesterZoneId(), loan.vehicleId(), loan.category()); // notifica all'initiator il completamento dell'intervento del veicolo sull'emergenza
                    }

                    long now = System.currentTimeMillis(); // orario attuale

                    // ripristina le informazioni dei veicoli

                    for (VehicleCheckpoint checkpoint : checkpointRepository.loadAll()) { // recupera dal db l'ultimo stato noto dei veicoli non AVAILABLE al momento del crash e li ricostruisce
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
                        if (checkpoint.status() == VehicleStatus.ON_SCENE && checkpoint.sceneStartedAtMillis() != null) { // se il veicolo era sull'emergenza e quindi stava iniziando l'intervento
                            /*
                            registra il momento di inizio intervento del veicolo sull'emergenza, per non perderlo nel caso nel caso in cui l'intervento non si è concluso e si verifichi un secondo crash
                            (senza questo salvataggio, la mappa che viene utilizzata in checkpointAllVehicles per il salvataggio del checkpoint sarebbe vuota - il nodo è stato resettato dopo il riavvio dal crash - e quindi non saprebbe da quale momento l'intervento del veicolo è iniziato)
                             */
                            lifecycleManager.registerSceneStart(checkpoint.vehicleId(), checkpoint.sceneStartedAtMillis());
                            if (checkpoint.sceneDeadlineMillis() != null) {
                                lifecycleManager.registerSceneDeadline(checkpoint.vehicleId(), checkpoint.sceneDeadlineMillis()); // registra il momento di fine intervento effettivo, per non perderlo nel caso nel caso in cui l'intervento non si è concluso e si verifichi un secondo crash
                            }
                            referenceTime = checkpoint.sceneStartedAtMillis(); // prende il tempo dall'inizio reale dell'intervento sull'emergenza
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