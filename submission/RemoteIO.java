import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketException;

public class RemoteIO {
    public Socket getSocket() {
        return remoteSocket;
    }

    Socket remoteSocket;
    BufferedReader bufferedReaderReader;
    PrintWriter printWriter;
    Logger logger;

    // creating a remoteIO
    public RemoteIO(Socket socket, int timeOut, Logger logger) {
        this.logger = logger;
        this.remoteSocket = socket;
        try {
            remoteSocket.setSoTimeout(timeOut);
        } catch (SocketException e) {
            e.printStackTrace();
        }
        try {
            bufferedReaderReader = new BufferedReader(new InputStreamReader(remoteSocket.getInputStream()));
            printWriter = new PrintWriter(remoteSocket.getOutputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    // checking the received message
    public void receive(String expected) throws Exception {
        String actual = read();
        logger.messageReceived(remoteSocket, actual);
        if (!actual.equals(expected)) {
            throw new Exception(actual);
        }
    }


    //use varargs
    public void writef(String message, Object... args) {
        write(String.format(message, args));
    }


    // read a message and return if we have a logger where to write to
    public String read() throws Exception {
        String msg = bufferedReaderReader.readLine();
        if (logger != null) {
            logger.messageReceived(remoteSocket, msg);
            return msg;
        }

        return null;
    }


    // writing helpful messages
    public void write(String message) {
        if (logger != null) logger.msg(remoteSocket, message);
        printWriter.println(message);
        printWriter.flush();
    }

}