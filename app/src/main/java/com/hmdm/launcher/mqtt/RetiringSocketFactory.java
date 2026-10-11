package com.hmdm.launcher.mqtt;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

/** Closing the factory also rejects sockets created by a late DNS/connect task. */
public final class RetiringSocketFactory {
    private final List<Socket> sockets = new ArrayList<>();
    private boolean retired;

    private synchronized Socket track(Socket socket) throws IOException {
        if (retired) {
            socket.close();
            throw new IOException("MQTT transport retired");
        }
        sockets.add(socket);
        return socket;
    }

    public synchronized void retire() {
        retired = true;
        for (Socket socket : sockets) {
            try { socket.close(); } catch (IOException ignored) {}
        }
        sockets.clear();
    }

    public SocketFactory wrap(SocketFactory delegate) {
        if (delegate instanceof SSLSocketFactory) return new Tls((SSLSocketFactory) delegate);
        return new Plain(delegate);
    }

    private class Plain extends SocketFactory {
        final SocketFactory delegate;
        Plain(SocketFactory delegate) { this.delegate = delegate; }
        public Socket createSocket() throws IOException { return track(delegate.createSocket()); }
        public Socket createSocket(String h, int p) throws IOException { return track(delegate.createSocket(h, p)); }
        public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException { return track(delegate.createSocket(h, p, l, lp)); }
        public Socket createSocket(InetAddress h, int p) throws IOException { return track(delegate.createSocket(h, p)); }
        public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws IOException { return track(delegate.createSocket(h, p, l, lp)); }
    }

    private class Tls extends SSLSocketFactory {
        final SSLSocketFactory delegate;
        Tls(SSLSocketFactory delegate) { this.delegate = delegate; }
        public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        public Socket createSocket() throws IOException { return track(delegate.createSocket()); }
        public Socket createSocket(Socket s, String h, int p, boolean close) throws IOException { return track(delegate.createSocket(s, h, p, close)); }
        public Socket createSocket(String h, int p) throws IOException { return track(delegate.createSocket(h, p)); }
        public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException { return track(delegate.createSocket(h, p, l, lp)); }
        public Socket createSocket(InetAddress h, int p) throws IOException { return track(delegate.createSocket(h, p)); }
        public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws IOException { return track(delegate.createSocket(h, p, l, lp)); }
    }
}
