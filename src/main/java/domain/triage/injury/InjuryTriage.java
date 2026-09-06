package domain.triage.injury;

import domain.emergency.Severity;
import domain.emergency.VehicleRequirement;
import domain.triage.common.Answer;
import domain.triage.common.TriageResult;
import domain.vehicle.VehicleCategory;
import java.util.List;

// classe di valutazione (gravita + veicoli) dell'emergenza
public class InjuryTriage {

    public static TriageResult evaluate(BodyPartCategory bodyPart, Answer conscious, Answer movement, Answer bleeding) {
        if (bodyPart == BodyPartCategory.LOW_RISK) {
            return evaluateLowRisk(conscious, movement, bleeding);
        }
        return evaluateHighRiskOrUnknown(conscious, movement, bleeding);
    }

    // Braccio / mano / Gamba / piede / Altro
    private static TriageResult evaluateLowRisk(Answer conscious, Answer movement, Answer bleeding) {
        // 1) alta
        boolean unconsciousOrUnknown = conscious == Answer.NO || conscious == Answer.DONT_KNOW;
        boolean isBleeding = bleeding == Answer.YES;
        boolean bleedingUnknownAndNoMovement = bleeding == Answer.DONT_KNOW && movement == Answer.NO;

        if (unconsciousOrUnknown || isBleeding || bleedingUnknownAndNoMovement) {
            return new TriageResult(Severity.HIGH, List.of(new VehicleRequirement(VehicleCategory.AMBULANCE, 1)));
        }

        // 2) media
        boolean bleedingUnknownAndMovementYesOrUnknown = bleeding == Answer.DONT_KNOW && (movement == Answer.YES || movement == Answer.DONT_KNOW);
        boolean bleedingNoAndNoMovement = bleeding == Answer.NO && movement == Answer.NO;

        if (bleedingUnknownAndMovementYesOrUnknown || bleedingNoAndNoMovement) {
            return new TriageResult(Severity.MEDIUM, List.of(new VehicleRequirement(VehicleCategory.AMBULANCE, 1)));
        }

        // 3) bassa
        boolean bleedingNoAndMovementYesOrUnknown = bleeding == Answer.NO && (movement == Answer.YES || movement == Answer.DONT_KNOW);

        if (bleedingNoAndMovementYesOrUnknown) {
            return new TriageResult(Severity.LOW, List.of(new VehicleRequirement(VehicleCategory.AMBULANCE, 1)));
        }

        // 4) non classificabile
        throw new IllegalStateException("Unreachable: injury classification incomplete");
    }

    // Testa / Collo / Schiena / Torace / Addome / Non lo so
    private static TriageResult evaluateHighRiskOrUnknown(Answer conscious, Answer movement, Answer bleeding) {
        // 1) alta
        boolean unconsciousOrUnknown = conscious == Answer.NO || conscious == Answer.DONT_KNOW;
        boolean noMovementUnknownAndBleedingYes = (movement == Answer.NO || movement == Answer.DONT_KNOW) && bleeding == Answer.YES;
        boolean noMovementAndBleedingYesOrUnknown = movement == Answer.NO && (bleeding == Answer.YES || bleeding == Answer.DONT_KNOW);
        boolean noMovementAndBleedingNoOrUnknown = movement == Answer.NO && (bleeding == Answer.NO || bleeding == Answer.DONT_KNOW);

        if (unconsciousOrUnknown || noMovementUnknownAndBleedingYes || noMovementAndBleedingYesOrUnknown || noMovementAndBleedingNoOrUnknown) {
            return new TriageResult(Severity.HIGH, List.of(new VehicleRequirement(VehicleCategory.AMBULANCE, 1)));
        }

        // 2) media
        boolean movementYesOrUnknownAndBleedingNoOrUnknown = (movement == Answer.YES || movement == Answer.DONT_KNOW) && (bleeding == Answer.NO || bleeding == Answer.DONT_KNOW);
        boolean movementYesOrUnknownAndBleedingYesOrUnknown = (movement == Answer.YES || movement == Answer.DONT_KNOW) && (bleeding == Answer.YES || bleeding == Answer.DONT_KNOW);

        if (movementYesOrUnknownAndBleedingNoOrUnknown || movementYesOrUnknownAndBleedingYesOrUnknown) {
            return new TriageResult(Severity.MEDIUM, List.of(new VehicleRequirement(VehicleCategory.AMBULANCE, 1)));
        }

        // 3) non classificabile
        throw new IllegalStateException("Unreachable: injury classification incomplete");
    }
}