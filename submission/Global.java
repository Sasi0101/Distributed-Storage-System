import java.util.Collection;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import java.util.function.Function;

public class Global {


    public static String IP_ADDRESS = "127.0.0.1";
    //Why does this one does not work???
    /*static {
        try {
            IP_ADDRESS = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }*/

    static BinaryOperator<String> stringWithSpace = (s1, s2) -> s1 + " " + s2;


    // handles the received messages
    public static void handleReceivedMessage(String message, Function<String[], Boolean> commandHandler, Consumer<String> error) {
        String[] command = splitWithSpaces(message);
        handleReceivedMessage(command, () -> commandHandler.apply(command), error);
    }

    public static void handleReceivedMessage(String[] command, Supplier<Boolean> tokenHandler, Consumer<String> errorLogger) {

        try {
            if (!tokenHandler.get()) {
                errorLogger.accept(command[0] + " is an undefined command"); // write if command not recognised
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            errorLogger.accept("Not the right number of arguments for: " + command[0]);
        }
    }


    // split using spaces
    public static String[] splitWithSpaces(String s) {
        return s.split("( )+");
    }


    // does it correctly
    public static String joinSpaceString(Collection col) {
        if (col.isEmpty()) return "";
        return (String) col.stream().map(Object::toString).reduce(stringWithSpace).get();

    }

    //IT WORKS!!!!!!!!
    public static<T> Set<T> setDifference(Set<T> a, Set<T> b) {
        return a.stream().filter(e -> !b.contains(e)).collect(Collectors.toSet());
    }

}
