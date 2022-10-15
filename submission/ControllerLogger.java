import java.net.Socket;

public class ControllerLogger extends Logger {

    private static ControllerLogger instance = null; // initial declaration of ControllerLogger


    public ControllerLogger(LoggingType loggingType) {
        super(loggingType);
    }


    @Override
    protected String loggerName() {
        return "controller";
    }

    // initialising it maybe add exception?
    public static void initialise(LoggingType loggingType) {
        // initialise a logger if there is not one yet
        if (instance == null)
            instance = new ControllerLogger(loggingType);

    }

    // return the ControllerLogger
    public static ControllerLogger instance() {
        if (instance == null) // check if we have one yet
            throw new RuntimeException("ControllerLogger has not been initialised yet");
        return instance;
    }





    //  what to write out when a dstore joins
    public void dstoreJoined(Socket socket, int dstorePort) {
        logging("[New Dstore " + dstorePort + " " + socket.getLocalPort() + "<-" + socket.getPort() + "]");
    }

}