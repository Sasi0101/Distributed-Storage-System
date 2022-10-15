import java.io.IOException;
import java.io.PrintStream;
import java.net.Socket;

public abstract class Logger {

    public enum LoggingType {
        ON_TERMINAL_ONLY, // log to System.out only
        ON_FILE_ONLY, // log to file only
        ON_FILE_AND_TERMINAL // log to both System.out and file
    }

    public final LoggingType loggingType; // get where we need to log
    public PrintStream printStream; // get the printstream

    //creating a logger
    public Logger(LoggingType loggingType) {
        this.loggingType = loggingType;
    }

    // so we can easily identifies the loggers
    protected abstract String loggerName();

    //getting the print stream
    protected synchronized PrintStream getPrintStream() throws IOException {
        if (printStream == null) printStream = new PrintStream(loggerName() + "_" + System.currentTimeMillis() + ".log");
        return printStream;
    }

    // decide if we are logging to a file
    protected boolean logToFile() {
        return loggingType == LoggingType.ON_FILE_ONLY || loggingType == LoggingType.ON_FILE_AND_TERMINAL;
    }

    //returns true if we are logging to a terminal
    protected boolean logToTerminal() {
        return loggingType == LoggingType.ON_TERMINAL_ONLY || loggingType == LoggingType.ON_FILE_AND_TERMINAL;
    }

    // logging it -- it works finally
    protected void logging(String message) {
        if (logToFile())
            try { getPrintStream().println(message); } catch(Exception e) { e.printStackTrace(); }
        if (logToTerminal())
            System.out.println(message);
    }

    //send the message
    public void msg(Socket socket, String message) {
        logging("[" + socket.getLocalPort() + "->" + socket.getPort() + "] " + message);
    }

    // receive the message, sometimes it receives wrong message --NEED TO FIX THAT
    public void messageReceived(Socket socket, String message) {
        logging("[" + socket.getLocalPort() + "<-" + socket.getPort() + "] " + message);
    }

}