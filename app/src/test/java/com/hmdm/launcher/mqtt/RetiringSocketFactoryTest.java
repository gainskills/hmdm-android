package com.hmdm.launcher.mqtt;

import org.junit.Test;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.Socket;
import static org.junit.Assert.*;

public class RetiringSocketFactoryTest {
    @Test public void retirementClosesSocketsAndRejectsLateConnectTasks() throws Exception {
        RetiringSocketFactory owner = new RetiringSocketFactory();
        SocketFactory factory = owner.wrap(SocketFactory.getDefault());
        Socket socket = factory.createSocket();
        owner.retire();
        assertTrue(socket.isClosed());
        try { factory.createSocket(); fail("Retired factory allowed a late socket"); }
        catch (IOException expected) {}
        owner.retire();
    }
    @Test public void tlsFactoryRetainsPahoSslTypeAndRetiresItsSockets() throws Exception {
        RetiringSocketFactory owner = new RetiringSocketFactory();
        SocketFactory factory = owner.wrap(SSLSocketFactory.getDefault());
        assertTrue(factory instanceof SSLSocketFactory);
        Socket socket = factory.createSocket();
        owner.retire();
        assertTrue(socket.isClosed());
    }
    @Test public void retirementRejectsSocketReturnedByAlreadyRunningFactory() throws Exception {
        java.util.concurrent.CountDownLatch creating = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Socket pending = new Socket();
        SSLSocketFactory delayed = new SSLSocketFactory() {
            public String[] getDefaultCipherSuites() { return new String[0]; }
            public String[] getSupportedCipherSuites() { return new String[0]; }
            public Socket createSocket() throws IOException {
                creating.countDown();
                try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Timed out"); }
                catch (InterruptedException e) { throw new IOException(e); }
                return pending;
            }
            public Socket createSocket(Socket s, String h, int p, boolean c) { throw new UnsupportedOperationException(); }
            public Socket createSocket(String h, int p) { throw new UnsupportedOperationException(); }
            public Socket createSocket(String h, int p, java.net.InetAddress l, int lp) { throw new UnsupportedOperationException(); }
            public Socket createSocket(java.net.InetAddress h, int p) { throw new UnsupportedOperationException(); }
            public Socket createSocket(java.net.InetAddress h, int p, java.net.InetAddress l, int lp) { throw new UnsupportedOperationException(); }
        };
        RetiringSocketFactory owner = new RetiringSocketFactory();
        SocketFactory factory = owner.wrap(delayed);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<Socket> connect = executor.submit(() -> factory.createSocket());
            assertTrue(creating.await(5, java.util.concurrent.TimeUnit.SECONDS));
            owner.retire();
            release.countDown();
            try { connect.get(5, java.util.concurrent.TimeUnit.SECONDS); fail("Late socket survived retirement"); }
            catch (java.util.concurrent.ExecutionException expected) { assertTrue(expected.getCause() instanceof IOException); }
            assertTrue(pending.isClosed());
        } finally { release.countDown(); executor.shutdownNow(); pending.close(); }
    }

}
