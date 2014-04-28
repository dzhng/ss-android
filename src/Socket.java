package com.ekoapp.core;

import android.content.Context;
import android.util.Log;

import com.ekoapp.eko.Utils.KeyStoreManager;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_17;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;


public class Socket {

    private static final String TAG = "SOCKET";

    private static final int RECONNECT_INTERVAL = 2000; //in ms

    private enum PacketType {
        PACKET_TYPE_OPEN,
        PACKET_TYPE_CLOSE,
        PACKET_TYPE_PING,
        PACKET_TYPE_PONG,
        PACKET_TYPE_MESSAGE,
        PACKET_TYPE_UPGRADE
    }

    private enum ConnectionState {
        CONNECTION_STATE_DEFAULT,
        CONNECTION_STATE_CONNECTED,
        CONNECTION_STATE_DISCONNECTED
    }

    // the code used when websocket close is requested by the client
    private int WEBSOCKET_CLOSE_CODE_NORMAL = 1000;

    // determine the state of the socket in order to decide when to reconnect
    private boolean socketShouldBeOpen = false;

    private EkoWebSocketClient client;
    private ConnectionState socketState = ConnectionState.CONNECTION_STATE_DEFAULT;
    private ConnectionState oldState = ConnectionState.CONNECTION_STATE_DEFAULT;
    private ScheduledExecutorService pingTimer, watchdogTimer;
    private boolean wasConnected, watchdogKicked;
    private boolean reconnectScheduled = false;
    private int pingInterval, pingTimeout;
    private Stream delegate;
    private URI connection;
    private Context context;

    public String sessionId;
    public int apiVersion;

    public Socket(Context context, String url, Stream delegate) {
        this.delegate = delegate;
        this.connection = URI.create(url);
        this.context = context;
    }

    public void disconnect() {
        socketShouldBeOpen = false;
        Log.d(TAG, "Closing socket");

        stopWatchdog();
        stopPing();

        client.close();

        setSocketState(ConnectionState.CONNECTION_STATE_DISCONNECTED);
    }

    public void connectToServer() {
        if (socketState == ConnectionState.CONNECTION_STATE_CONNECTED) { return; }
        socketShouldBeOpen = true;
        client = new EkoWebSocketClient(connection, sessionId, apiVersion);

        SSLContext sslContext = null;
        try {
            sslContext = SSLContext.getInstance( "TLS" );
            sslContext.init(null, KeyStoreManager.trustManagers(context), null);
            SSLSocketFactory factory = sslContext.getSocketFactory();   // (SSLSocketFactory) SSLSocketFactory.getDefault();
            client.setSocket(factory.createSocket());
            client.connect();
        } catch (Exception e) {
            Log.e(TAG, "Couldn't open socket: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void sendMessage(String message) {
            String toSend = PacketType.PACKET_TYPE_MESSAGE.ordinal() + message;
            if (socketShouldBeOpen && socketState == ConnectionState.CONNECTION_STATE_CONNECTED) {
                try {
                    client.send(toSend);
                }
                catch (WebsocketNotConnectedException e) {
                    Log.w(TAG, "Websocket disconnected, trying to reconnect");
                    tryReconnect();
                }
            }
    }

    private void setSocketState(ConnectionState state) {
        if (oldState == state) return;
        oldState = socketState = state;

        if (state == ConnectionState.CONNECTION_STATE_CONNECTED ) {
            if (wasConnected) {
                delegate.didReconnect();
            }
            else {
                delegate.didConnect();
            }
            wasConnected = true;
        }
        else if (state == ConnectionState.CONNECTION_STATE_DISCONNECTED) {
            delegate.didDisconnect();
        }
    }

    private void parseOpenPacketJSON(String data) {
        try {
            JSONObject jObj = new JSONObject(data);
            pingInterval = jObj.getInt("pingInterval");
            pingTimeout = jObj.getInt("pingTimeout");
        } catch (JSONException e1) {
            e1.printStackTrace();
        }
    }

    private void tryReconnect() {
        if (!socketShouldBeOpen) {
            return;
        }
        if (!reconnectScheduled) {
            Log.v(TAG, "Reconnect scheduled...");
            reconnectScheduled = true;
            ScheduledExecutorService reconnectTimer = Executors.newSingleThreadScheduledExecutor();
            reconnectTimer.schedule(new Runnable() {
                @Override
                public void run() {
                    if (socketShouldBeOpen) {
                        connectToServer();
                    }
                    reconnectScheduled = false;
                }
            }, RECONNECT_INTERVAL, TimeUnit.MILLISECONDS);
        }
    }

    private void performPingAtInterval(int interval) {
        final String pingMessage = "2ping"; // Ping packet
        stopPing();

        pingTimer = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture timerFuture = pingTimer.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                client.send(pingMessage);
            }
        }, 0, interval, TimeUnit.MILLISECONDS);
    }

    private void stopPing() {
        if (pingTimer != null) {
            pingTimer.shutdownNow();
            pingTimer = null;
        }
    }

    private void setupWatchdogAtInterval(int interval) {
        stopWatchdog();

        watchdogTimer = Executors.newSingleThreadScheduledExecutor();
        watchdogTimer.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (!watchdogKicked) {
                    Log.w(TAG, "Watchdog is shutting down socket: server unresponsive");
                    client.close();
                    stopWatchdog();
                    stopPing();
                }
                watchdogKicked = false;
            }
        }, 0, interval, TimeUnit.MILLISECONDS);

        kickWatchdog();
    }

    private void kickWatchdog() {
        watchdogKicked = true;
    }

    private void stopWatchdog() {
        if (watchdogTimer != null) {
            watchdogTimer.shutdownNow();
            watchdogTimer = null;
        }
    }


    class EkoWebSocketClient extends WebSocketClient {

        public EkoWebSocketClient(URI serverUri, final String sessionId, final int apiVersion) {
            super(serverUri, new Draft_17(), new HashMap<String, String>() {{
                put("Cookie", "sessionId=" + sessionId + "; apiVersion=" + apiVersion);
            }}, 0);
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
            Log.d(TAG, "Status: Connected to " + connection);
            setSocketState(ConnectionState.CONNECTION_STATE_CONNECTED);
        }

        @Override
        public void onMessage(String payload) {
            int value = payload.charAt(0) - 48;
            PacketType type = PacketType.values()[value];
            String data = payload.substring(1);

            switch(type) {
                case PACKET_TYPE_OPEN:
                    parseOpenPacketJSON(data);
                    performPingAtInterval(pingInterval);
                    setupWatchdogAtInterval(pingTimeout);
                    kickWatchdog();
                    break;
                case PACKET_TYPE_CLOSE: // close packet
                    try {
                        client.closeBlocking();
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Couldn't close socket: " + e.getMessage());
                    }
                    break;
                case PACKET_TYPE_PING:
                    // send back a pong packet
                    client.send("3probe");
                    break;
                case PACKET_TYPE_PONG:
                    // don't do anything for pong packets, since it's probably just the server
                    // responding to our regular pings
                    break;
                case PACKET_TYPE_MESSAGE:
                    delegate.didReceiveMessage(data);
                    break;
                default:
                    Log.d(TAG, "Unknown packet type: "+ type);
                    break;
            }
            kickWatchdog();
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            Log.w(TAG, "Connection lost: " + code + " | " + reason);
            setSocketState(ConnectionState.CONNECTION_STATE_DISCONNECTED);

            stopWatchdog();
            stopPing();

            // try reconnecting if socket should still be open
            if (socketShouldBeOpen && code != WEBSOCKET_CLOSE_CODE_NORMAL) {
                tryReconnect();
            }
        }

        @Override
        public void onError(Exception e) {
            Log.e(TAG, "Error with WebSocketClient: " + e.getMessage());
            e.printStackTrace();
        }
    }

}
