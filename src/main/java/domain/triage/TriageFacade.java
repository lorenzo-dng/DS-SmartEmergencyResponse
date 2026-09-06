package domain.triage;

import domain.triage.assault.AssaultAnswers;
import domain.triage.assault.AssaultTriage;
import domain.triage.cardiacarrest.CardiacArrestAnswers;
import domain.triage.cardiacarrest.CardiacArrestTriage;
import domain.triage.common.TriageResult;
import domain.triage.fire.FireAnswers;
import domain.triage.fire.FireTriage;
import domain.triage.injury.InjuryAnswers;
import domain.triage.injury.InjuryTriage;
import domain.triage.powergridfailure.PowerGridFailureAnswers;
import domain.triage.powergridfailure.PowerGridFailureTriage;
import domain.triage.roadaccident.RoadAccidentAnswers;
import domain.triage.roadaccident.RoadAccidentTriage;
import domain.triage.roadobstruction.RoadObstructionAnswers;
import domain.triage.roadobstruction.RoadObstructionTriage;

// può essere vista come un'interfaccia, che in base all'emergenza richiama il suo metodo di valutazione (gravita + veicoli)
public class TriageFacade {

    // answers non contiene dati, non viene utilizzato. Tuttavia è necessario per mantenere la stessa firma del metodo classify con tutti gli altri
    public static TriageResult classify(CardiacArrestAnswers answers) {
        return CardiacArrestTriage.evaluate();
    }

    public static TriageResult classify(RoadAccidentAnswers answers) {
        return RoadAccidentTriage.evaluate(answers.numVehicles(), answers.injuries(), answers.trapped());
    }

    public static TriageResult classify(InjuryAnswers answers) {
        return InjuryTriage.evaluate(answers.bodyPart(), answers.conscious(), answers.movement(), answers.bleeding());
    }

    public static TriageResult classify(AssaultAnswers answers) {
        return AssaultTriage.evaluate(answers.peopleInvolved(), answers.injuries());
    }

    public static TriageResult classify(FireAnswers answers) {
        return FireTriage.evaluate(answers.fireType(), answers.size(), answers.dangerToPeopleOrBuildings());
    }

    public static TriageResult classify(RoadObstructionAnswers answers) {
        return RoadObstructionTriage.evaluate(answers.obstacleType(), answers.roadBlocked(), answers.injuries());
    }

    public static TriageResult classify(PowerGridFailureAnswers answers) {
        return PowerGridFailureTriage.evaluate(answers.extent(), answers.exposedWires());
    }
}