package dev.autorecorder.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Local line-delimited JSON protocol. Binds loopback only; one JSON event per line. */
public final class EventServer implements AutoCloseable {
    private static final Set<String> TYPES=Set.of("ATTEMPT_START","DIMENSION","ATTEMPT_END","SESSION_START","SESSION_END","HEARTBEAT");
    private final int port; private final Consumer<JsonNode> handler; private final ObjectMapper mapper=new ObjectMapper();
    private volatile ServerSocket server; private volatile boolean running;
    public EventServer(int port,Consumer<JsonNode> handler){this.port=port;this.handler=handler;}
    public void start(){Thread t=new Thread(()->{try(ServerSocket s=new ServerSocket()){server=s;s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(),port));running=true;while(running){Socket c=s.accept();Thread client=new Thread(()->serve(c),"ipc-client");client.setDaemon(true);client.start();}}catch(IOException ignored){}},"ipc-server");t.setDaemon(true);t.start();}
    private void serve(Socket c){try(c;BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream()));BufferedWriter w=new BufferedWriter(new OutputStreamWriter(c.getOutputStream()))){c.setSoTimeout(5000);String line;while((line=r.readLine())!=null){try{if(line.length()>8192)throw new IllegalArgumentException("message too large");JsonNode n=mapper.readTree(line);String type=n.path("type").asText();if(!TYPES.contains(type)||!n.path("protocol").asText().equals("mcsr-autorecorder/1"))throw new IllegalArgumentException("unsupported event");handler.accept(n);w.write("{\"ok\":true}\n");}catch(Exception e){w.write("{\"ok\":false,\"error\":\"invalid event\"}\n");}w.flush();}}catch(IOException ignored){}}
    @Override public void close(){running=false;try{if(server!=null)server.close();}catch(IOException ignored){}}
}
