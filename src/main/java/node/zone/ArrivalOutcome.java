package node.zone;

// esito della richiesta di un initiator di accettare l'arrivo sulla scena di un veicolo prestato
public enum ArrivalOutcome {
    ADMITTED, // l'arrivo viene accettato e registrato
    ALREADY_REGISTERED, // l'arrivo era già stato registrato e il gruppo di veicoli della stessa categoria ha già fissato la deadline, quindi la notifica del contractor è un duplicato da ignorare
    REJECTED // il veicolo non serve (emergenza non più aperta o slot già coperto)
}