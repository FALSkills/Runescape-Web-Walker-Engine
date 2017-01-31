package scripts.webwalker_logic.local.walker_engine.object_handling;

import org.tribot.api.types.generic.Filter;
import org.tribot.api2007.Game;
import org.tribot.api2007.Objects;
import org.tribot.api2007.Player;
import org.tribot.api2007.ext.Filters;
import org.tribot.api2007.types.*;
import scripts.webwalker_logic.local.walker_engine.Loggable;
import scripts.webwalker_logic.local.walker_engine.NPCInteraction;
import scripts.webwalker_logic.local.walker_engine.WaitFor;
import scripts.webwalker_logic.local.walker_engine.WalkerEngine;
import scripts.webwalker_logic.local.walker_engine.bfs.BFS;
import scripts.webwalker_logic.local.walker_engine.local_pathfinding.PathAnalyzer;
import scripts.webwalker_logic.local.walker_engine.real_time_collision.RealTimeCollisionTile;

import java.util.*;
import java.util.stream.Collectors;


public class ObjectHandler implements Loggable {

    private static ObjectHandler instance;

    private final TreeSet<String> sortedOptions, sortedBlackList, sortedHighPriorityOptions;

    private ObjectHandler(){
        sortedOptions = new TreeSet<>(Arrays.asList("Enter", "Cross", "Pass", "Open", "Close", "Walk-through", "Use",
                "Walk-Across", "Walk-across", "Climb", "Climb-up", "Climb-down", "Climb-over", "Climb-into", "Climb-through",
                "Jump-from", "Squeeze-through", "Jump-over", "Pay-toll(10gp)", "Step-over", "Walk-down", "Walk-up", "Travel"));
        sortedBlackList = new TreeSet<>(Arrays.asList("Coffin"));
        sortedHighPriorityOptions = new TreeSet<>(Arrays.asList("Pay-toll(10gp)"));
    }

    private static ObjectHandler getInstance (){
        return instance != null ? instance : (instance = new ObjectHandler());
    }

    private enum SpecialObject {
        ROOTS("Roots", "Chop", null, new SpecialCondition() {
            @Override
            boolean isSpecialLocation(PathAnalyzer.DestinationDetails destinationDetails) {
                return Objects.find(15,
                        Filters.Objects.inArea(new RSArea(destinationDetails.getAssumed(), 1))
                                .combine(Filters.Objects.nameEquals("Roots"), true)
                                .combine(Filters.Objects.actionsContains("Chop"), true)).length > 0;
            }
        }),
        ROOT("Root", "Step-over", null, new SpecialCondition() {
            @Override
            boolean isSpecialLocation(PathAnalyzer.DestinationDetails destinationDetails) {
                return Objects.find(15,
                        Filters.Objects.inArea(new RSArea(destinationDetails.getAssumed(), 1))
                                .combine(Filters.Objects.nameEquals("Root"), true)
                                .combine(Filters.Objects.actionsContains("Step-over"), true)).length > 0;
            }
        }),
        BRIMHAVEN_VINES("Vines", "Chop-down", null, new SpecialCondition() {
            @Override
            boolean isSpecialLocation(PathAnalyzer.DestinationDetails destinationDetails) {
                return Objects.find(15,
                        Filters.Objects.inArea(new RSArea(destinationDetails.getAssumed(), 1))
                                .combine(Filters.Objects.nameEquals("Vines"), true)
                                .combine(Filters.Objects.actionsContains("Chop-down"), true)).length > 0;
            }
        }),
        AVA_BOOKCASE ("Bookcase", "Search", new RSTile(3097, 3359, 0), new SpecialCondition() {
            @Override
            boolean isSpecialLocation(PathAnalyzer.DestinationDetails destinationDetails) {
                return destinationDetails.getDestination().getX() >= 3097 && destinationDetails.getAssumed().equals(new RSTile(3097, 3359, 0));
            }
        }),
        AVA_LEVER ("Lever", "Pull", new RSTile(3096, 3357, 0), new SpecialCondition() {
            @Override
            boolean isSpecialLocation(PathAnalyzer.DestinationDetails destinationDetails) {
                return destinationDetails.getDestination().getX() < 3097 && destinationDetails.getAssumed().equals(new RSTile(3097, 3359, 0));
            }
        });

        private String name, action;
        private RSTile location;
        private SpecialCondition specialCondition;
        SpecialObject(String name, String action, RSTile location, SpecialCondition specialCondition){
            this.name = name;
            this.action = action;
            this.location = location;
            this.specialCondition = specialCondition;
        }

        public String getName() {
            return name;
        }

        public String getAction() {
            return action;
        }

        public RSTile getLocation() {
            return location;
        }

        public boolean isSpecialCondition(PathAnalyzer.DestinationDetails destinationDetails){
            return specialCondition.isSpecialLocation(destinationDetails);
        }

        public static SpecialObject getValidSpecialObjects(PathAnalyzer.DestinationDetails destinationDetails){
            for (SpecialObject object : values()){
                if (object.isSpecialCondition(destinationDetails)){
                    return object;
                }
            }
            return null;
        }

    }

    private abstract static class SpecialCondition {
        abstract boolean isSpecialLocation(PathAnalyzer.DestinationDetails destinationDetails);
    }

    public static boolean handle(PathAnalyzer.DestinationDetails destinationDetails, ArrayList<RSTile> path){
        RealTimeCollisionTile start = destinationDetails.getDestination(), end = destinationDetails.getNextTile();

        RSObject[] interactiveObjects = null;

        String action = null;
        SpecialObject specialObject = SpecialObject.getValidSpecialObjects(destinationDetails);
        if (specialObject == null) {
            if ((interactiveObjects = getInteractiveObjects(start.getX(), start.getY(), start.getZ(), destinationDetails)).length < 1 && end != null) {
                interactiveObjects = getInteractiveObjects(end.getX(), end.getY(), end.getZ(), destinationDetails);
            }
        } else {
            action = specialObject.getAction();
            Filter<RSObject> specialObjectFilter = Filters.Objects.nameEquals(specialObject.getName())
                    .combine(Filters.Objects.actionsContains(specialObject.getAction()), true)
                    .combine(Filters.Objects.inArea(new RSArea(specialObject.getLocation() != null ? specialObject.getLocation() : destinationDetails.getAssumed(), 1)), true);
            interactiveObjects = Objects.findNearest(15, specialObjectFilter);
        }

        if (interactiveObjects.length < 1) {
            return false;
        }

        StringBuilder stringBuilder = new StringBuilder("Sort Order: ");
        Arrays.stream(interactiveObjects).forEach(rsObject -> stringBuilder.append(rsObject.getDefinition().getName()).append(" ").append(Arrays.asList(rsObject.getDefinition().getActions())).append(", "));
        getInstance().log(stringBuilder);

        return handle(path, interactiveObjects[0], destinationDetails, action);
    }

    private static boolean handle(ArrayList<RSTile> path, RSObject object, PathAnalyzer.DestinationDetails destinationDetails, String action){
        PathAnalyzer.DestinationDetails current = PathAnalyzer.furthestReachableTile(path);

        if (current == null){
            return false;
        }

        RealTimeCollisionTile currentFurthest = current.getDestination();
        if (!Player.isMoving() && (!object.isOnScreen() || !object.isClickable())){
            if (!WalkerEngine.getInstance().clickMinimap(destinationDetails.getDestination())){
                return false;
            }
        }
        if (WaitFor.condition(10000, () -> object.isOnScreen() && object.isClickable() ? WaitFor.Return.SUCCESS : WaitFor.Return.IGNORE) != WaitFor.Return.SUCCESS) {
            return false;
        }
        boolean successfulClick = false;
        for (int i = 0; i < 6; i++) {
            String[] validOptions = action != null ? new String[]{action} : getViableOption(Arrays.stream(object.getDefinition().getActions()).filter(getInstance().sortedOptions::contains).collect(Collectors.toList()), destinationDetails);
            if (clickOnObject(object, validOptions)){
                successfulClick = true;
                break;
            }
            if (i > 2){
                WaitFor.milliseconds(400 * (i - 2), 800 * (i - 2));
            }
        }

        if (isStrongholdDoor(object)){
            if (!handleStrongholdQuestions(destinationDetails)){
                return false;
            }
        }

        if (successfulClick) {
            WaitFor.condition(15000, () -> {
                DoomsToggle.handleToggle();
                PathAnalyzer.DestinationDetails destinationDetails1 = PathAnalyzer.furthestReachableTile(path);
                if (NPCInteraction.isConversationWindowUp()) {
                    NPCInteraction.handleConversation(NPCInteraction.GENERAL_RESPONSES);
                }
                if (destinationDetails1 != null) {
                    if (!destinationDetails1.getDestination().equals(currentFurthest)){
                        return WaitFor.Return.SUCCESS;
                    }
                }
                if (current.getNextTile() != null){
                    PathAnalyzer.DestinationDetails hoverDetails = PathAnalyzer.furthestReachableTile(path, current.getNextTile());
                    if (hoverDetails != null && hoverDetails.getDestination() != null){
                        WalkerEngine.getInstance().hoverMinimap(hoverDetails.getDestination());
                    }
                }
                return WaitFor.Return.IGNORE;
            });
            WaitFor.milliseconds(400, 800);
            return true;
        }
        return false;
    }

    public static RSObject[] getInteractiveObjects(int x, int y, int z, PathAnalyzer.DestinationDetails destinationDetails){
        RSObject[] objects = Objects.getAll(25, interactiveObjectFilter(x, y, z, destinationDetails));
        final RSTile base = new RSTile(x, y, z);
        Arrays.sort(objects, (o1, o2) -> {
            int c = Integer.compare(o1.getPosition().distanceTo(base), o2.getPosition().distanceTo(base));
            int assumedZ = destinationDetails.getAssumedZ(), destinationZ = destinationDetails.getDestination().getZ();
            List<String> actions1 = Arrays.asList(o1.getDefinition().getActions());
            List<String> actions2 = Arrays.asList(o2.getDefinition().getActions());

            if (assumedZ > destinationZ){
                if (actions1.contains("Climb-up")){
                    return -1;
                }
                if (actions2.contains("Climb-up")){
                    return 1;
                }
            }

            if (assumedZ < destinationZ){
                if (actions1.contains("Climb-down")){
                    return -1;
                }
                if (actions2.contains("Climb-down")){
                    return 1;
                }
            }
            return c;
        });
        StringBuilder a = new StringBuilder("Detected: ");
        Arrays.stream(objects).forEach(object -> a.append(object.getDefinition().getName()).append(" "));
        getInstance().log(a);
        return objects;
    }


    private static Filter<RSObject> interactiveObjectFilter(int x, int y, int z, PathAnalyzer.DestinationDetails destinationDetails){
        final RSTile position = new RSTile(x, y, z);
        return new Filter<RSObject>() {
            @Override
            public boolean accept(RSObject rsObject) {
                if (rsObject.getPosition().distanceTo(destinationDetails.getDestination().getRSTile()) > 5){
                    return false;
                }
                if (Arrays.stream(rsObject.getAllTiles()).noneMatch(rsTile -> rsTile.distanceTo(position) <= 2)){
                    return false;
                }
                RSObjectDefinition def = rsObject.getDefinition();
                if (def == null){
                    return false;
                }
                if (getInstance().sortedBlackList.contains(def.getName())){
                    return false;
                }
                List<String> options = Arrays.asList(def.getActions());
                return options.stream().anyMatch(getInstance().sortedOptions::contains);
            }
        };
    }

    private static String[] getViableOption(Collection<String> collection, PathAnalyzer.DestinationDetails destinationDetails){
        Set<String> set = new HashSet<>(collection);
        if (set.retainAll(getInstance().sortedHighPriorityOptions) && set.size() > 0){
            return set.toArray(new String[set.size()]);
        }
        if (destinationDetails.getAssumedZ() > destinationDetails.getDestination().getZ()){
            if (collection.contains("Climb-up")){
                return new String[]{"Climb-up"};
            }
        }
        if (destinationDetails.getAssumedZ() < destinationDetails.getDestination().getZ()){
            if (collection.contains("Climb-down")){
                return new String[]{"Climb-down"};
            }
        }
        if (destinationDetails.getAssumedY() > 5000 && destinationDetails.getDestination().getZ() == 0 && destinationDetails.getAssumedZ() == 0){
            if (collection.contains("Climb-down")){
                return new String[]{"Climb-down"};
            }
        }
        String[] options = new String[collection.size()];
        collection.toArray(options);
        return options;
    }

    private static boolean clickOnObject(RSObject object, String[] options){
        boolean result;

        if (isClosedTrapDoor(object, options)){
            result = handleTrapDoor(object);
        } else {
            result = object.click(options);
            getInstance().log("Interacting with (" + object.getDefinition().getName() + ") at " + object.getPosition() + " with options: " + Arrays.toString(options) + " " + (result ? "SUCCESS" : "FAIL"));
        }

        return result;
    }

    private static boolean isStrongholdDoor(RSObject object){
        List<String> doorNames = Arrays.asList("Gate of War", "Rickety door", "Oozing barrier", "Portal of Death");
        return  doorNames.contains(object.getDefinition().getName());
    }


    private static boolean handleStrongholdQuestions(PathAnalyzer.DestinationDetails destinationDetails) {
        return WaitFor.condition(10000, () -> {
            RSTile playerPosition = Player.getPosition();
            if (BFS.isReachable(RealTimeCollisionTile.get(playerPosition.getX(), playerPosition.getY(), playerPosition.getPlane()), destinationDetails.getNextTile(), 50)) {
                WaitFor.milliseconds(500, 1000);
                return WaitFor.Return.SUCCESS;
            }
            if (NPCInteraction.isConversationWindowUp()) {
                return WaitFor.Return.SUCCESS;
            }
            return WaitFor.Return.IGNORE;
        }) == WaitFor.Return.SUCCESS && (!NPCInteraction.isConversationWindowUp()
                || NPCInteraction.handleConversation("Virus scan my computer then change my password.",
                "No.",
                "No",
                "Report the incident and do not click any links.",
                "Don't click any links, forward the email to reportphishing@jagex.com.",
                "Through account settings on runescape.com.",
                "Set up 2 step authentication with my email provider",
                "Nobody", "Secure my computer and reset my RuneScape password.",
                "No, it might steal my password.",
                "Politely tell them no and then use the 'Report Abuse' button.",
                "Don't tell them anything and click the 'Report Abuse' button.",
                "No, you should never buy a Runescape account",
                "No, you should never allow anyone to level your account",
                "The birthday of a famous person or event.",
                "Talk to any banker in RuneScape.",
                "Only on the RuneScape website.",
                "Inform Jagex by emailing reportphishing@jagex.com",
                "Don't give him my password.",
                "Nowhere.",
                "Don't give them the information and send an 'Abuse report'.",
                "Use the Account Recovery System."));


    }

    private static boolean isClosedTrapDoor(RSObject object, String[] options){
        return  (object.getDefinition().getName().equals("Trapdoor") && Arrays.asList(options).contains("Open"));
    }

    private static boolean handleTrapDoor(RSObject object){
        if (getActions(object).contains("Open")){
            if (!clickObjectAndWait(object, "Open", () -> {
                RSObject[] objects = Objects.find(15, Filters.Objects.actionsContains("Climb-down").combine(Filters.Objects.inArea(new RSArea(object, 2)), true));
                if (objects.length > 0 && getActions(objects[0]).contains("Climb-down")){
                    return WaitFor.Return.SUCCESS;
                }
                return WaitFor.Return.IGNORE;
            })){
                return false;
            } else {
                RSObject[] objects = Objects.find(15, Filters.Objects.actionsContains("Climb-down").combine(Filters.Objects.inArea(new RSArea(object, 2)), true));
                return objects.length > 0 && handleTrapDoor(objects[0]);
            }

        }
        getInstance().log("Interacting with (" + object.getDefinition().getName() + ") at " + object.getPosition() + " with option: Climb-down");
        return object.click("Climb-down");
    }

    public static List<String> getActions(RSObject object){
        List<String> list = new ArrayList<>();
        if (object == null){
            return list;
        }
        RSObjectDefinition objectDefinition = object.getDefinition();
        if (objectDefinition == null){
            return list;
        }
        String[] actions = objectDefinition.getActions();
        if (actions == null){
            return list;
        }
        return Arrays.asList(actions);
    }

    public static boolean clickObjectAndWait(RSObject object, String action, WaitFor.Condition condition) {
        return object.click(action) && WaitFor.condition(10000, condition) == WaitFor.Return.SUCCESS;
    }

    @Override
    public String getName() {
        return "Object Handler";
    }
}
