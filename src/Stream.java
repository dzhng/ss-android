package com.ekoapp.core;

import android.content.Context;
import android.util.Log;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ObjectNode;
import org.mozilla.javascript.Callable;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;


public class Stream {
    private static final String TAG = "STREAM";

    public interface StreamDelegate {
        // returns the cookie value for sending over to the server
        public String didSetSessionId(Stream stream, String sid);
        public void streamDidConnect(Stream stream);
        public void streamDidReconnect(Stream stream);
        public void streamDidDisconnect(Stream stream);
    }

    public static abstract class StreamRunnable {
        public abstract void run(Object[] results);
    }

    public StreamDelegate delegate;

    private static final ObjectMapper mapper = new ObjectMapper();

    private HashMap<String, ArrayList<StreamRunnable>> bindCallbacks = new HashMap<String, ArrayList<StreamRunnable>>();
    private HashMap<Integer, StreamRunnable> rpcCallbacks = new HashMap<Integer, StreamRunnable>();
    private int rpcId = 1;

    private static final char RESPONDER_TYPE_EVENT = '0';
    private static final char RESPONDER_TYPE_RPC = '1';
    private static final char RESPONDER_TYPE_SYSTEM = 'X';
    private Socket socket;

    public Stream(Context context, String host, int port, boolean secure) {
        String connection = String.format("%s://%s:%d", secure ? "wss" : "ws", host, port);
        socket = new Socket(context, connection, this);
    }

    public void didReceiveMessage(String message) {
        try {
            char type = message.charAt(0);
            String data = message.substring(2);

            switch(type) {
                case RESPONDER_TYPE_EVENT:
                    parseEventJSON(data);
                    break;
                case RESPONDER_TYPE_RPC:
                    parseRpcJSON(data);
                    break;
                case RESPONDER_TYPE_SYSTEM:
                    try {
                        NativeObject parsed = (NativeObject)Core.getInstance().javascriptStringToObject(data);
                        if (parsed instanceof NativeObject) {
                            String sessionId = parsed.get("sessionId").toString();
                            String cookieValue = this.delegate.didSetSessionId(this, sessionId);

                            // inform socket of new session cookie for reconnects
                            if (cookieValue != null) {
                                socket.sessionId = cookieValue;
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    break;
                default:
                    Log.e(TAG, "Undefined responder type" + type);
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void parseEventJSON(String data) {
        try {
            // clean event footer from data
            int idx = data.lastIndexOf("|");
            if (idx == -1) {
                Log.e(TAG, "Improperly formatted event string");
                return;
            }

            String jsonData = data.substring(0, idx);
            NativeObject parsed = (NativeObject)Core.getInstance().javascriptStringToObject(jsonData);
            if (parsed instanceof NativeObject) {
                String channel = parsed.get("e").toString();
                NativeArray results = (NativeArray)parsed.get("p");
                ArrayList<StreamRunnable> callbacks = bindCallbacks.get(channel);
                if (callbacks != null) {
                    for(StreamRunnable callback: callbacks) {
                        callback.run(results == null ? null : results.toArray());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void parseRpcJSON(String data) {
        try {
            NativeObject parsed = (NativeObject)Core.getInstance().javascriptStringToObject(data);
            if (parsed instanceof NativeObject) {
                int rid = ((Double)parsed.get("id")).intValue();
                NativeArray results = (NativeArray)parsed.get("p");
                StreamRunnable callback = rpcCallbacks.get(rid);
                if (callback != null) {
                    callback.run(results == null ? null : results.toArray());
                    rpcCallbacks.remove(rid);
                }
                else {
                    Log.w(TAG, "RPC Callback for id " + rid + " does not exist!");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // parse input JsonNode into Java primitives: Map, List, Boolean, Number, or Null
    private Object parseJSON(JsonNode json) {
        if (json.isContainerNode()) {
            if (json.isArray()) {
                List<Object> results = new ArrayList<Object>();
                Iterator<JsonNode> resultsIterator = json.getElements();
                while (resultsIterator.hasNext()) {
                    JsonNode node = resultsIterator.next();
                    results.add(parseJSON(node));
                }
                return results;
            }
            // object would correspond to Map
            else if (json.isObject()) {
                Map<String, Object> results = new HashMap<String, Object>();
                Iterator<Map.Entry<String, JsonNode>> objectIterator = json.getFields();
                while (objectIterator.hasNext()) {
                    Map.Entry<String, JsonNode> e = objectIterator.next();
                    results.put(e.getKey(), parseJSON(e.getValue()));
                }
                return results;
            }
        }
        else if (json.isValueNode()) {
            if (json.isInt()) {
                return json.getIntValue();
            }
            else if (json.isDouble()) {
                return json.getDoubleValue();
            }
            else if (json.isBoolean()) {
                return json.getBooleanValue();
            }
            else if (json.isTextual()) {
                return json.getTextValue();
            }
        }

        // if we can't parse it, just mark it as null
        return null;
    }

    public void didDisconnect() {
        delegate.streamDidDisconnect(this);
    }

    public void didReconnect() {
        delegate.streamDidReconnect(this);
    }

    public void didConnect() {
        delegate.streamDidConnect(this);
    }

    private void sendMessage(String message, char type)
    {
        String toSend = type + "|" + message;
        socket.sendMessage(toSend);
    }

    public void setApiVersion(int apiVersion) {
        socket.apiVersion = apiVersion;
    }

    public void setSessionId(String sessionId) {
        socket.sessionId = sessionId;

    }

    public void disconnect() {
        socket.disconnect();
    }

    public void connectToServer() {
        socket.connectToServer();
    }

    public void bindWithCallback(String channel, StreamRunnable callback) {
        // add callback to dictionary
        ArrayList<StreamRunnable> callbackArray = bindCallbacks.get(channel);
        if (callbackArray == null) {
            callbackArray = new ArrayList<StreamRunnable>();
        }
        callbackArray.add(callback);
        bindCallbacks.put(channel, callbackArray);
    }

    public void rpcWithParametersAndCallback(String method, Object params, StreamRunnable callback) {
        // create data and call server
        ObjectNode objNode = mapper.createObjectNode();
        objNode.put("id", rpcId);
        objNode.put("m", method);
        objNode.putPOJO("p", params);
        try {
            String message = mapper.writeValueAsString(objNode);
            Log.d(TAG, message);
            sendMessage(message, RESPONDER_TYPE_RPC);
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "Error sending message");
        }
        // add callback to dictionary, with rpcId as key
        rpcCallbacks.put(rpcId, callback);
        // increment rpcID for next call
        rpcId++;
    }

}
