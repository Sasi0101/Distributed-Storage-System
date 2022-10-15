import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.CountDownLatch;



public class Dstore implements Runnable{

    int cport; // where it communicates with the controller
    int dStorePort; //the dstores port
    int timeout; // timeout
    RemoteIO controllerIO;
    RemoteIO clientIO;

    String folderName;

    Map<String, Integer> fileSizeMap = new HashMap<>();

    public Dstore(int port, int cport, int timeout, String folder, Logger.LoggingType loggingType) {
        this.dStorePort = port;
        this.cport = cport;
        this.timeout = timeout;
        this.folderName = folder;

        try {
            DstoreLogger.initialise(loggingType, port); // initialising a DstoreLogger
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    //returns the filepath
    public String getFilePath(String fileName) {
        return folderName + '/' + fileName;
    }

    // store a file
    public void storeFile(String fileName, String contents) {
        try {
            FileWriter writer = new FileWriter(getFile(fileName));
            writer.write(contents);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    //remove a file
    protected void removeFile(String file) {
        fileSizeMap.remove(file);
        getFile(file).delete(); // deleting the files
        DstoreLogger.instance().logging("Removing file " + file);
    }

    public RemoteIO newRemoteIO(Socket socket) {
        return new RemoteIO(socket, timeout, DstoreLogger.instance());
    }

    public void storeFromClient(String fileName, String size, boolean rebalance) throws Exception {

        fileSizeMap.put(fileName, Integer.parseInt(size));

        clientIO.write("ACK"); // acknowledging it

        String content = clientIO.read(); // getting the content

        storeFile(fileName, content);

        if (!rebalance) controllerIO.write("STORE_ACK " + fileName);


    }

    protected void storeIt(String file, Integer whereTo) throws IOException {
        RemoteIO recipientIO;

        recipientIO = newRemoteIO(new Socket(Global.IP_ADDRESS, whereTo));

        String content = getData(file);
        try {
            assert content != null;
            recipientIO.write( String.join(" ", "REBALANCE_STORE", file, Integer.toString(content.length())));

        } catch (NullPointerException e) {
            e.printStackTrace();
        }
        try {
            recipientIO.receive("ACK");
        } catch (Exception e) {
            logError("Expected ACK_TOKEN.");
        }
        recipientIO.write(content);
        try {
            recipientIO.getSocket().close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        DstoreLogger.instance().logging("Storing file " + file + " to " + whereTo);
    }
    // lets hope synchronized solves the problem and handles the clients well
    public synchronized void handleClients(String message)  {
        Global.handleReceivedMessage(message, tokens ->
        {
            String command = tokens[0];
            if(command.contains("LOAD_DATA")) {
                loadFrom(tokens[1]);
            }
            if(command.contains("REBALANCE_STORE")) {
                try {
                    storeFromClient(tokens[1], tokens[2], true);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if(command.contains("STORE")) {
                try {
                    storeFromClient(tokens[1], tokens[2], false);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            return true;
        }, this::logError);
    }

    public synchronized void handleControllers(String message) {
        Global.handleReceivedMessage(message, reply -> {
            String received = reply[0];
            if(received.contains("REMOVE")) remove4Controller(reply[1]);
            if(received.contains("LIST")) controllerIO.write("LIST" + ' ' + Global.joinSpaceString(fileSizeMap.keySet()));
            if(received.contains("REBALANCE")) rebalanceController(Arrays.copyOfRange(reply, 1, reply.length));

            return true;
        }, this::logError);

    }

    public void rebalanceController(String[] args) {
        int i = 0;
        int n = Integer.parseInt(args[i++]);
        for (int j = n; j > 0; j--) {
            String fileName = args[i++];
            final int toStore = Integer.parseInt(args[i++]);

            Set<Integer> toSend = new TreeSet<>();
            for (int send = toStore; send > 0; --send) {
                Integer dStore2Store = Integer.parseInt(args[i++]);
                toSend.add(dStore2Store);
            }

            CountDownLatch countDownLatch = new CountDownLatch(toSend.size());
            for (var dStore2Store:toSend) {
                new Thread(() -> {
                    try {
                        storeIt(fileName, dStore2Store);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    countDownLatch.countDown();
                }).start();
            }

            try {
                countDownLatch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        int toRemove = Integer.parseInt(args[i++]);
        for (int k = toRemove; k > 0; k--) {
            removeFile(args[i++]);
        }
        controllerIO.write("REBALANCE_COMPLETE");
    }

    public void loadFrom(String fileName) {
        String fileData = getData(fileName);
        // if the returned value is null we close the socket
        if (fileData == null) {
            try {
                clientIO.getSocket().close(); // closing the socket
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        clientIO.write(fileData);
    }

    public File getFile(String fileName) {
        return new File(getFilePath(fileName));
    }

    public String getData(String file) {
        try {
            FileReader reader = new FileReader(getFilePath(file));
            char[] caracter = new char[fileSizeMap.get(file)];
            reader.read(caracter); // reading
            reader.close(); // closing it
            // return the string we need
            return String.copyValueOf(caracter);

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public void logError(String message) {
        DstoreLogger.instance().logging("[ERROR] " + message);
    }

    public void remove4Controller(String fileName) {

        if (!fileSizeMap.containsKey(fileName)) {
            controllerIO.write("ERROR_FILE_DOES_NOT_EXIST" + ' ' + fileName);
        }
        controllerIO.write("REMOVE_ACK" + ' ' + fileName);


        removeFile(fileName);
    }


    public void run() {
        // Initialize a folder
        File folder = new File(folderName);
        if (!folder.mkdir()) {
            Arrays.stream(Objects.requireNonNull(folder.listFiles())).forEach(f -> fileSizeMap.put(f.getName(), (int) f.length()));
        }

        try {
            // Connecting to a controller
            Socket controller = null;
            while (controller == null) {
                try {

                    controller = new Socket(Global.IP_ADDRESS, cport);
                    DstoreLogger.instance().logging("Dstore " + dStorePort + " is " + controller.getLocalPort());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            // Deal with a client
            ServerSocket mySocket = new ServerSocket(dStorePort);
            new Thread(() -> {
                String message = null;
                while (true) {
                    Socket clientSocket = null;
                    try {
                        clientSocket = mySocket.accept();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    clientIO = newRemoteIO(clientSocket);
                    do {
                        try {
                            message = clientIO.read();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    } while (message == null);

                    handleClients(message);
                }

            }).start();


            DstoreLogger.instance().logging("DSTORE port is " + dStorePort);
            controllerIO = newRemoteIO(controller); // create a new remoteIO
            controllerIO.write("JOIN " + dStorePort); // JOIN

            // getting the controllers message
            String msg = null;
            do {
                try {
                    msg = controllerIO.read();
                    handleControllers(msg);
                } catch (Exception e) {
                    e.printStackTrace();
                }

            } while (msg != null); // doing it until we have message


        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        Logger.LoggingType logging = (args.length == 5 && args[4].equals("-noLogFile")) ? Logger.LoggingType.ON_TERMINAL_ONLY : Logger.LoggingType.ON_FILE_AND_TERMINAL;
        new Dstore(Integer.parseInt(args[0]), Integer.parseInt(args[1]), Integer.parseInt(args[2]), args[3],
                logging).run();
    }
}
