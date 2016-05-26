import java.applet.Applet;
import java.awt.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;

public class ClassifierApplet extends Applet {

    private final static int NUM_PARALLEL = 2; // number of simultaneous filtering/allocation discovery pairs to make

    private final static int UDP_RECV_BUF_SIZE = 2048;
    private final static int UDP_RECV_TIMEOUT = 10 * 1000;
    private final static int UDP_RECV_MAX_TIMEOUT = 20 * 1000;
    private final static int UDP_MAX_RETRY = 3;

    private final static String PRIMARY_IP = "52.27.15.59";
    private final static String SECONDARY_IP = "52.26.32.86";
    private final static String[] IP_LIST = {PRIMARY_IP, PRIMARY_IP, SECONDARY_IP};
    private final static int[] PORT_ALLOC = {2000, 2001, 2002};
    private final static int[] PORT_FILTER = {3000, 3001, 3002};

    private final static SocketAddress PRIMARY_PORT2_ADDR = new InetSocketAddress(IP_LIST[1], PORT_ALLOC[1]);
    private final static SocketAddress SECONDARY_PORT1_ADDR = new InetSocketAddress(IP_LIST[2], PORT_ALLOC[2]);

    private Result result;

    private boolean started = false;

    private static ArrayList<DatagramSocket> openedSockets = new ArrayList();

    // IMP: enum values should be in decreasing order of strictness
    enum FilterType {
        PORT, ADDRESS, NONE
    }

    // IMP: enum values should be in decreasing order of strictness
    enum AllocType {
        PORT, ADDRESS, NONE
    }

    private static class Result {
        FilterType filterType;
        AllocType allocType;
        int progression = Integer.MAX_VALUE; // if allocType = PORT | ADDRESS
    }

    @Override
    public void init() {
    }

    @Override
    public void paint(Graphics g) {
        if (result!= null) {
            g.drawString("Alloc: " + result.allocType, 10, 20);
            g.drawString("Progression: " + result.progression, 10, 40);
            g.drawString("Filter: " + result.filterType, 10, 60);
            return;
        }
        if (started) {
            return;
        }
        started = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                result = main();
                ClassifierApplet.this.repaint();
            }
        });
        thread.start();
    }

    public static Result main() {
        final ArrayList<Result> results = new ArrayList();
        ArrayList<Thread> threads = new ArrayList();
        for (int i = 0; i < NUM_PARALLEL; i++) {
            Thread th = new Thread(new Runnable() {
                @Override
                public void run() {
                    Result result = determine();
                    synchronized (results) {
                        results.add(result);
                    }
                }
            });
            th.start();
            threads.add(th);
        }
        for (Thread th : threads) {
            try {
                th.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        closeOpenedSockets();

        return mergeResults(results);
    }

    private static void closeOpenedSockets() {
        for(DatagramSocket socket: openedSockets) {
            socket.close();
        }
        openedSockets = new ArrayList();
    }

    private static Result mergeResults(ArrayList<Result> results) {
        Result finalResult = new Result();
        for (Result result : results) {
            // merging the filter
            if (result.filterType != null) {
                if (finalResult.filterType == null) {
                    finalResult.filterType = result.filterType;
                } else {
                    // favor the more lenient result, the strict results could be due to dropped UDP packets
                    finalResult.filterType = FilterType.values()[
                            Math.max(finalResult.filterType.ordinal(), result.filterType.ordinal())
                            ];
                }
            }

            // merging the allocation
            if (result.allocType != null) {
                if (finalResult.allocType == null) {
                    finalResult.allocType = result.allocType;
                    finalResult.progression = result.progression;
                } else {
                    // favor the more lenient result, the strict results could be due to dropped UDP packets
                    finalResult.allocType = AllocType.values()[
                            Math.max(finalResult.allocType.ordinal(), result.allocType.ordinal())
                            ];
                    finalResult.progression
                            = Math.abs(result.progression) < Math.abs(finalResult.progression) ?
                            result.progression : finalResult.progression;
                }
            }
        }

        return finalResult;
    }

    public static Result determine() {
        final Result result = new Result();
        Thread th1 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    addFilterDetails(result);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        th1.start();
        Thread th2 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    addAllocDetails(result);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        th2.start();
        try {
            th1.join();
            th2.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * ASSUMPTION: PORT RESTRICTED implies ADDRESS RESTRICTED ALLOCATION!
     *
     * @throws IOException
     */
    public static void addAllocDetails(Result result) throws IOException {
        DatagramSocket datagramSocket = getRandomDatagramSocket();
        System.out.println("Searching for alloc details with port " + datagramSocket.getPort());
        ArrayList<Integer> responses = new ArrayList();
        for (int i = 0; i < IP_LIST.length; i++) {
            // since we have only one UDP response to wait for, UDP_RECV_TIMEOUT is sufficient
            HashMap<SocketAddress, Integer> tempResponses = sendUdp(IP_LIST[i], PORT_ALLOC[i], datagramSocket,
                    UDP_RECV_TIMEOUT, 1);
            responses.addAll(tempResponses.values());
            System.out.println("Responses for port " +datagramSocket.getPort() + ": " + tempResponses.values());
        }

        if (responses.size() < IP_LIST.length) {
            // we failed :/
            System.out.println("Failed alloc details. Only " + responses.size() + " responses received :/");
            return;
        } else if (responses.size() > IP_LIST.length) {
            System.out.println("ERROR! Alloc response size " + responses.size());
        }

        synchronized (result) {
            int diff1 = Integer.MAX_VALUE;
            int diff2;
            if (!responses.get(0).equals(responses.get(1))) {
                result.allocType = AllocType.PORT;
                diff1 = responses.get(1) - responses.get(0);
                diff2 = responses.get(2) - responses.get(1);
            } else if (!responses.get(1).equals(responses.get(2))) {
                result.allocType = AllocType.ADDRESS;
                diff2 = responses.get(2) - responses.get(1);
            } else {
                result.allocType = AllocType.NONE;
                diff1 = diff2 = 0;
            }
            result.progression = Math.abs(diff1) > Math.abs(diff2) ? diff2 : diff1;
        }
    }

    private static DatagramSocket getRandomDatagramSocket() throws SocketException {
        int fromPort = 10000 + Math.round((float) Math.random() * 55000);
        DatagramSocket datagramSocket = new DatagramSocket(null);
        datagramSocket.setReuseAddress(true);
        datagramSocket.bind(new InetSocketAddress(fromPort));
        openedSockets.add(datagramSocket);
        return datagramSocket;
    }

    /**
     * ASSUMPTIONS: Filtered by port implies filtered by address also
     *
     * @throws IOException
     */
    public static void addFilterDetails(Result result) throws IOException {
        DatagramSocket datagramSocket = getRandomDatagramSocket();
        HashMap<SocketAddress, Integer> responses = sendUdp(
                IP_LIST[0], PORT_FILTER[0],
                datagramSocket,
                UDP_RECV_MAX_TIMEOUT,
                3);
        if (responses.size() == 0) {
            System.out.println("Connection failed for port " + datagramSocket.getPort());
        }
        synchronized (result) {
            if (responses.containsKey(SECONDARY_PORT1_ADDR)) {
                result.filterType = FilterType.NONE;
            } else if (responses.containsKey(PRIMARY_PORT2_ADDR)) {
                result.filterType = FilterType.ADDRESS;
            } else {
                result.filterType = FilterType.PORT;
            }
        }
        datagramSocket.close();
    }

    public static HashMap<SocketAddress, Integer> sendUdp(String toIp, int toPort,
                                                          DatagramSocket socket,
                                                          int maxResponseTime,
                                                          int maxResponses)
            throws IOException {
        InetSocketAddress toAddr = new InetSocketAddress(toIp, toPort);
        HashMap<SocketAddress, Integer> responses = new HashMap();
        for (int i = 0; i < UDP_MAX_RETRY; i++) {
            byte[] sendBuf = new byte[0];
            DatagramPacket packet = new DatagramPacket(sendBuf, sendBuf.length);
            packet.setSocketAddress(toAddr);
            socket.send(packet);
            responses.putAll(accumulateUniqueUdpResponses(socket, maxResponseTime, maxResponses));
            if (responses.size() != 0) break;
        }

        return responses;
    }

    public static HashMap<SocketAddress, Integer> accumulateUniqueUdpResponses(DatagramSocket socket,
                                                                               final int maxResponseTime,
                                                                               final int maxResponses) throws IOException {
        byte[] recvBuf = new byte[UDP_RECV_BUF_SIZE];
        DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
        socket.setSoTimeout(UDP_RECV_TIMEOUT);

        long endTime = System.currentTimeMillis() + maxResponseTime;

        HashMap<SocketAddress, Integer> responseSet = new HashMap();

        try {
            while (responseSet.keySet().size() < maxResponses) {
                socket.receive(packet);
                try {
                    responseSet.put(
                            packet.getSocketAddress(),
                            Integer.parseInt(
                                    new String(packet.getData(), packet.getOffset(), packet.getLength(), Charset.forName("UTF-8"))
                            )
                    );
                } catch (NumberFormatException e) {
                    // bad packet, passed checksum though
                    e.printStackTrace();
                }
                if (System.currentTimeMillis() > endTime) {
                    break;
                }
            }
        } catch (SocketTimeoutException e) {
        }

        return responseSet;
    }
}
