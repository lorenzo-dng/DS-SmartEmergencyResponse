package communication.emergency;

import domain.common.Position;
import domain.emergency.EmergencyType;
import domain.triage.TriageFacade;
import domain.triage.assault.AssaultAnswers;
import domain.triage.assault.PeopleInvolved;
import domain.triage.common.Answer;
import domain.triage.common.TriageResult;
import domain.triage.fire.FireAnswers;
import domain.triage.fire.FireSize;
import domain.triage.fire.FireType;
import domain.triage.injury.BodyPartCategory;
import domain.triage.injury.InjuryAnswers;
import domain.triage.powergridfailure.PowerGridExtent;
import domain.triage.powergridfailure.PowerGridFailureAnswers;
import domain.triage.roadaccident.RoadAccidentAnswers;
import domain.triage.roadaccident.VehiclesInvolved;
import domain.triage.roadobstruction.ObstacleType;
import domain.triage.roadobstruction.RoadObstructionAnswers;
import io.vertx.core.json.JsonObject;

// gui -> nodo: gestisce la segnalazione di una nuova emergenza
public record EmergencyRequest(EmergencyType type, Position position, TriageResult triage) {

    // legge e valida il messaggio json
    public static EmergencyRequest fromJson(JsonObject json) {
        if (json != null) {
            try {
                EmergencyType type = enumValue(json, "type", EmergencyType.class);
                Position position = new Position(coordinate(json, "lat", -90, 90), coordinate(json, "lon", -180, 180));
                JsonObject answers = json.getJsonObject("answers"); // vuoto per cardiac arrest
                return new EmergencyRequest(type, position, classify(type, answers != null ? answers : new JsonObject())); // passa un oggetto vuoto per il caso di cardiac arrest
            } catch (
                    ClassCastException e) { // un campo ha un tipo diverso da quello atteso (ad esempio un numero al posto di una stringa)
                throw new IllegalArgumentException("Invalid field type in request: " + e.getMessage());
            }
        } else {
            throw new IllegalArgumentException("Missing request body");
        }
    }

    // legge una coordinata e verifica che sia un numero compreso nell'intervallo indicato
    private static double coordinate(JsonObject json, String key, double min, double max) {
        Double value = json.getDouble(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        if (value.isNaN() || value < min || value > max) {
            throw new IllegalArgumentException("Invalid value for field " + key + ": " + value);
        }
        return value;
    }

    // calcola il triage dell'emergenza
    private static TriageResult classify(EmergencyType type, JsonObject answers) {
        return switch (type) {
            case CARDIAC_ARREST -> TriageFacade.classify();
            case ROAD_ACCIDENT -> TriageFacade.classify(new RoadAccidentAnswers(
                    enumValue(answers, "numVehicles", VehiclesInvolved.class),
                    enumValue(answers, "injuries", Answer.class),
                    enumValue(answers, "trapped", Answer.class)));
            case INJURY -> TriageFacade.classify(new InjuryAnswers(
                    enumValue(answers, "bodyPart", BodyPartCategory.class),
                    enumValue(answers, "conscious", Answer.class),
                    enumValue(answers, "movement", Answer.class),
                    enumValue(answers, "bleeding", Answer.class)));
            case ASSAULT -> TriageFacade.classify(new AssaultAnswers(
                    enumValue(answers, "peopleInvolved", PeopleInvolved.class),
                    enumValue(answers, "injuries", Answer.class)));
            case FIRE -> TriageFacade.classify(new FireAnswers(
                    enumValue(answers, "fireType", FireType.class),
                    enumValue(answers, "size", FireSize.class),
                    enumValue(answers, "dangerToPeopleOrBuildings", Answer.class)));
            case ROAD_OBSTRUCTION -> TriageFacade.classify(new RoadObstructionAnswers(
                    enumValue(answers, "obstacleType", ObstacleType.class),
                    enumValue(answers, "roadBlocked", Answer.class),
                    enumValue(answers, "injuries", Answer.class)));
            case POWER_GRID_FAILURE -> TriageFacade.classify(new PowerGridFailureAnswers(
                    enumValue(answers, "extent", PowerGridExtent.class),
                    enumValue(answers, "exposedWires", Answer.class)));
        };
    }

    // legge un campo di tipo enum
    private static <E extends Enum<E>> E enumValue(JsonObject json, String key, Class<E> enumType) {
        String value = json.getString(key);
        if (value != null) {
            try {
                return Enum.valueOf(enumType, value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid value for field " + key + ": " + value);
            }
        } else {
            throw new IllegalArgumentException("Missing field: " + key);
        }
    }
}