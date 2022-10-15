import java.io.IOException;

public class DstoreLogger extends Logger {


    private static DstoreLogger instance = null;

    private final String dStorePort;

    // logger
    protected DstoreLogger(LoggingType loggingType, int port) {
        super(loggingType);
        dStorePort = "dstore" + "_" + port;
    }

    @Override
    protected String loggerName() {
        return dStorePort;
    }

    // needed exception handling
    public static void initialise(LoggingType loggingType, int port) throws IOException {
        if (instance == null) // check if we did not initialise it yet
            instance = new DstoreLogger(loggingType, port);
        else
            throw new IOException("DstoreLogger already initialised");
    }

    // returning the instance
    public static DstoreLogger instance() {
        if (instance == null) // if we don't have one that is a problem
            throw new RuntimeException("DstoreLogger has not been initialised yet");
        return instance;
    }



}