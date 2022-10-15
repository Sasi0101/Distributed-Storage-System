import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class Controller implements Runnable{
    ControllerLogger logger;
    private static final int DSTORE_THREADS = 35;

    private void remove(String fileName) {
        index.removeFile(fileName);
        fileSizeMap.remove(fileName);
    }

    int listenPort;
    SortedMap<Integer, RemoteIO> dStoreMap = new TreeMap<>();
    private Index index;
    private final int timeOut;
    private final int R;
    AtomicLong lastRebalanceTime = new AtomicLong();
    int rebalanceTime;
    Map<String, Integer> fileSizeMap = new HashMap<>();
    final Object clientLock = new Object();
    ExecutorService dStoreThreads = Executors.newFixedThreadPool(DSTORE_THREADS);

    //
    public Controller(int port, int R, int timeOut, int rebalance, Logger.LoggingType loggingType) {
        this.timeOut = timeOut;
        this.R = R;
        this.listenPort = port;
        this.index = new Index(R);
        this.rebalanceTime = rebalance;
        // intialise a new logger
        try {
            ControllerLogger.initialise(loggingType);
            logger = ControllerLogger.instance();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public void run() {
        System.out.println("Controller started.");

        // need to start a thread for rebalancing
        TimerTask rebalanceTask = new TimerTask() {
            @Override
            public void run() {
                if (System.currentTimeMillis() - lastRebalanceTime.get() > rebalanceTime) {
                    try {
                        rebalanceIt();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    lastRebalanceTime.set(System.currentTimeMillis());
                }
            }
        };


        Timer rebalanceTimer = new Timer();
        if (rebalanceTime > 0) {
            Thread rebalanceThread = new Thread( () -> rebalanceTimer.schedule(rebalanceTask, rebalanceTime, rebalanceTime));
            rebalanceThread.setName("rebalancer");
            rebalanceThread.start();
        }

        try {
            ServerSocket socket = new ServerSocket(listenPort);
            // this loop will never exit but that should not be a problem
            while (true) {
                RemoteIO outSocketIo = newRemoteIO(socket.accept());
                String[] whatRead = Global.splitWithSpaces(outSocketIo.read());
                if (whatRead[0].equals("JOIN")) {
                    joinDstores(whatRead[1], outSocketIo);
                } else {
                    ClientHandler clientHandler = new ClientHandler(outSocketIo, whatRead);
                    new Thread(clientHandler).start();
                }

            }


        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    // joining a new dstore
    private void joinDstores(String port, RemoteIO dStoreIo) throws Exception {
        synchronized (clientLock) {
            int intPort = Integer.parseInt(port);
            dStoreMap.put(intPort, dStoreIo);
            index.addDstore(intPort);
            ControllerLogger.instance().dstoreJoined(dStoreIo.getSocket(), intPort);
            rebalanceIt();
        }
    }

    public void logErrors(String err) {
        ControllerLogger.instance().logging("[ERROR] " + err);
    }


    private void forEachDstore(Consumer<Integer> func) {
        everyDstore(index.getDStores(), func);
    }

    // for every dstore
    private void everyDstore(Collection<Integer> dStores, Consumer<Integer> dStoreFunc) {
        CountDownLatch newLatch = new CountDownLatch(dStores.size());
        dStores.forEach(ds -> dStoreThreads.execute(() -> {
            dStoreFunc.accept(ds);
            newLatch.countDown();
        }));
        try {
            newLatch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    //returns what dstores each file is stored on
    private Index getCurrentIndex() {
            TreeMap<String, ArrayList<Integer>> storage = new TreeMap<>();
            ArrayList<Integer> dStoreList = new ArrayList<>();

            everyDstore(index.getDStores(), d -> {
                RemoteIO dStoreIO = dStoreMap.get(d);

                dStoreIO.write("LIST");
                String reply;
                try {
                    reply = dStoreIO.read();
                } catch (Exception e) {
                    logErrors("Did not get response from DSTORE " + d);
                    return;
                }
                dStoreList.add(d); // dstore replied

                String[] whatGotBack = Global.splitWithSpaces(reply);
                String[] files = Arrays.copyOfRange(whatGotBack, 1, whatGotBack.length);

                Stream.of(files).forEach(fName -> {
                    synchronized (storage) {
                        storage.putIfAbsent(fName, new ArrayList<>());
                        storage.get(fName).add(d);
                    }
                });
            });

        Set<String> missingFiles = Global.setDifference(fileSizeMap.keySet(), index.getFiles());
            missingFiles.forEach(f -> fileSizeMap.remove(f));
            return new Index(storage, dStoreList, R);

    }

    // more or less works i can not find out what the problem is
    public Map<Integer, RebalanceDef> findRebalances(Index current, Map<String, Index.Diff<Integer>> diff) {
        Map<Integer, RebalanceDef> rebalances = new TreeMap<>();
        for (var dstore: current.getDStores()) rebalances.put(dstore, new RebalanceDef());

        current.getFiles().forEach(file -> {
            Integer n = current.chooseDStoreForLoad(file);
            rebalances.get(n).addSendFileTo(file, diff.get(file).toAdd);
            (diff.get(file).toRemove).forEach(d -> rebalances.get(d).addFileToRemove(file));

        });

        return rebalances;
    }

    public void makeIndexCurrent(Index index) {
        this.index = index;
    }

    // needed for the rebalance
    public void rebalanceApplication(Map<Integer, RebalanceDef> rebalanceInfo) {
        forEachDstore(d -> {
            if ((rebalanceInfo.get(d)).isEmpty()) return; // return null if the info is null
            String message = "REBALANCE" + ' ' + rebalanceInfo.get(d);
            RemoteIO io = dStoreMap.get(d);
            io.write(message);

            try {
                io.receive("REMOVE_COMPLETE");
            } catch (Exception e) {
                logErrors("Expected REBALANCE_COMPLETE but got something else.");
            }

        });
    }
    //rebalancing it
    public void rebalanceIt() throws Exception {
        ControllerLogger.instance().logging("Rebalance in progress");
        // synchronized is required
        synchronized (clientLock) {
            if (index.notEnoughDstores()) return; // not enough dstores just return null
            Index current = getCurrentIndex();
            Index newOne = current.rebalanced();

            // check if we have enough Dstores
            if (newOne.notEnoughDstores()) {
                ControllerLogger.instance().logging("Need more Dstores to join");
                return;
            }

            Map<String, Index.Diff<Integer>> diffs = newOne.diff(current);

            if (diffs.isEmpty())
                return;

            rebalanceApplication(findRebalances(current,diffs));
            makeIndexCurrent(newOne);
        }
    }

    public static void main(String[] args) {
        //create the logger and decide the loggingType
        Logger.LoggingType logging = (args.length == 5 && args[4].equals("-noLogFile")) ? Logger.LoggingType.ON_TERMINAL_ONLY : Logger.LoggingType.ON_FILE_AND_TERMINAL;
        new Controller(Integer.parseInt(args[0]), Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]), logging).run();
    }

    // creating a new RemoteIO
    public RemoteIO newRemoteIO(Socket socket) throws Exception {
        if(socket == null) throw new Exception("Socket is null can not create RemoteIO");
        return new RemoteIO(socket, timeOut, ControllerLogger.instance());
    }

    class ClientHandler implements Runnable{

        RemoteIO clientIO;
        String[] messages;


        public ClientHandler(RemoteIO clientIO1, String[] message) {
            clientIO = clientIO1;
            this.messages = message;
        }

        @Override
        public void run() {
            handleClientMessage(messages);
            String clientMessage = null;

            while (true) {
                try {
                    if ((clientMessage = clientIO.read()) == null) break;
                } catch (Exception e) {
                    e.printStackTrace();
                }

                assert clientMessage != null;
                messages = Global.splitWithSpaces(clientMessage);
                handleClientMessage(messages);
            }
        }



        // handling a message works altough one time it receives a STORE instead of a LOAD but idk why
        public void handleClientMessage(String[] msgTokens) {
            synchronized (clientLock) {
                // if not enough dstores no need to further handle the message
                if (dStoreMap.size() < R) {
                    clientIO.write("ERROR_NOT_ENOUGH_DSTORES");
                    return;
                }
                // handling received message
                Global.handleReceivedMessage(msgTokens, () -> {
                    String message = msgTokens[0];
                    if(message.contains("ERROR_LOAD")) logger.logging("Recevied ERROR_LOAD from client " + clientIO.getSocket().getPort());
                    if(message.contains("LOAD")) loadClient(msgTokens[1]);
                    if(message.contains("STORE")) storeClient(msgTokens[1], msgTokens[2]);
                    if(message.contains("REMOVE")) removeClient(msgTokens[1]);
                    if(message.contains("LIST")) listClient();

                    return true;
                }, Controller.this::logErrors);
            }

        }

        //listing
        public void listClient() {
            clientIO.write("LIST " + Global.joinSpaceString(index.getFiles()) );
        }

        // loading a file
        public void loadClient(String fileName) {
            // checking if the file exists
            if (!index.containsFile(fileName)) {
                clientIO.write("ERROR_FILE_DOES_NOT_EXIST");
                return;
            }

            Integer port = index.chooseDStoreForLoad(fileName);
            String size = String.valueOf(fileSizeMap.get(fileName));
            clientIO.writef("LOAD_FROM " + port + " " + size);
        }

        // stoing works fine
        public void storeClient(String fileName, String fileSize) {
            // check if contains the file name to decide if we need to store it or not
            if (index.containsFile(fileName)) {
                clientIO.write("ERROR_FILE_ALREADY_EXISTS");
                return;
            }
            //store(fileName, f)
            Set<Integer> dStores = index.store(fileName);
            fileSizeMap.put(fileName, Integer.parseInt(fileSize));

            clientIO.write("STORE_TO" + ' ' + Global.joinSpaceString(dStores));



            everyDstore(dStores, d -> {
                RemoteIO dStoreIo = dStoreMap.get(d);
                try {
                    dStoreIo.receive("STORE_ACK" + ' ' + fileName);
                } catch (Exception e) {
                    logErrors("Expected STORE_ACK from Dstore but got ");
                }
            });
            clientIO.write("STORE_COMPLETE");
        }

        // removing a client works
        private void removeClient(String fileName) {
            Collection<Integer> dStores = index.getDStoresForFile(fileName);
            if (!index.containsFile(fileName)) {
                clientIO.write("ERROR_FILE_DOES_NOT_EXIST");
                return;
            }

            //sending it to dstores
            everyDstore(dStores, d -> {
                RemoteIO remoteIO = dStoreMap.get(d);

                remoteIO.write("REMOVE" + ' ' + fileName);
                try {
                    remoteIO.receive("REMOVE_ACK" + ' ' + fileName);
                } catch (Exception e) {
                    logErrors("Expected remove ack from DStore but received ");
                }
            });

            //remove the file
            remove(fileName);
            clientIO.write("REMOVE_COMPLETE"); // acknowledging the file
        }

    }
}
