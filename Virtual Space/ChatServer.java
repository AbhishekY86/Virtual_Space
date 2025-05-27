import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChatServer extends WebSocketServer {
    private static final Logger logger = LoggerFactory.getLogger(ChatServer.class);
    private Map<WebSocket, String> usernames;
    private Map<String, String> userAvatars;
    private Map<String, Set<WebSocket>> typingUsers;
    private Map<Long, Set<String>> messageReadBy;
    private Map<String, String> userContacts;

    public ChatServer(InetSocketAddress address) {
        super(address);
        usernames = new ConcurrentHashMap<>();
        userAvatars = new ConcurrentHashMap<>();
        typingUsers = new ConcurrentHashMap<>();
        messageReadBy = new ConcurrentHashMap<>();
        userContacts = new ConcurrentHashMap<>();
        logger.info("ChatServer initialized on {}", address);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        logger.info("New connection from {}", conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String username = usernames.get(conn);
        if (username != null) {
            logger.info("User {} disconnected: code={}, reason={}", username, code, reason);
            broadcastMessage("leave", username, null, null);
            usernames.remove(conn);
            userAvatars.remove(username);
            userContacts.remove(username);
            typingUsers.remove(username);
            broadcastUserListUpdate();
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JSONObject jsonMessage = new JSONObject(message);

            if (!jsonMessage.has("type")) {
                logger.warn("Message missing 'type' field: {}", message);
                sendError(conn, "Missing 'type' field");
                return;
            }

            String type = jsonMessage.getString("type");
            String username = jsonMessage.optString("username", "");

            if (!type.equals("join") && username.isEmpty()) {
                logger.warn("Message missing or empty 'username': {}", message);
                sendError(conn, "Missing or empty 'username'");
                return;
            }

            logger.debug("Received message from {}: type={}", username, type);

            switch (type) {
                case "join":
                    if (!jsonMessage.has("contact")) {
                        sendError(conn, "Missing 'contact' field");
                        return;
                    }
                    handleJoin(conn, username, jsonMessage.optString("avatar", ""),
                            jsonMessage.optString("contact", ""));
                    break;
                case "message":
                    if (!jsonMessage.has("message") || !jsonMessage.has("messageId")) {
                        sendError(conn, "Missing 'message' or 'messageId' field");
                        return;
                    }
                    handleChatMessage(username, jsonMessage.getString("message"), jsonMessage.getLong("messageId"));
                    break;
                case "file":
                    if (!jsonMessage.has("fileUrl") || !jsonMessage.has("isImage") || !jsonMessage.has("messageId")) {
                        sendError(conn, "Missing 'fileUrl', 'isImage', or 'messageId' field");
                        return;
                    }
                    handleFileMessage(username, jsonMessage.getString("fileUrl"), jsonMessage.getBoolean("isImage"),
                            jsonMessage.getLong("messageId"));
                    break;
                case "leave":
                    handleLeave(conn, username);
                    break;
                case "location":
                    if (!jsonMessage.has("latitude") || !jsonMessage.has("longitude")) {
                        sendError(conn, "Missing 'latitude' or 'longitude' field");
                        return;
                    }
                    handleLocationMessage(username, jsonMessage.getDouble("latitude"),
                            jsonMessage.getDouble("longitude"));
                    break;
                case "delete":
                    if (!jsonMessage.has("messageId")) {
                        sendError(conn, "Missing 'messageId' field");
                        return;
                    }
                    handleDeleteMessage(username, jsonMessage.getLong("messageId"));
                    break;
                case "edit":
                    if (!jsonMessage.has("messageId") || !jsonMessage.has("newMessage")) {
                        sendError(conn, "Missing 'messageId' or 'newMessage' field");
                        return;
                    }
                    handleEditMessage(username, jsonMessage.getLong("messageId"), jsonMessage.getString("newMessage"));
                    break;
                case "typing":
                    if (!jsonMessage.has("isTyping")) {
                        sendError(conn, "Missing 'isTyping' field");
                        return;
                    }
                    handleTyping(username, jsonMessage.getBoolean("isTyping"));
                    break;
                case "reaction":
                    if (!jsonMessage.has("messageId") || !jsonMessage.has("emoji")) {
                        sendError(conn, "Missing 'messageId' or 'emoji' field");
                        return;
                    }
                    handleReaction(username, jsonMessage.getLong("messageId"), jsonMessage.getString("emoji"));
                    break;
                case "read":
                    if (!jsonMessage.has("messageId")) {
                        sendError(conn, "Missing 'messageId' field");
                        return;
                    }
                    handleReadReceipt(username, jsonMessage.getLong("messageId"));
                    break;
                default:
                    logger.warn("Unknown message type: {}", type);
                    sendError(conn, "Unknown message type: " + type);
                    break;
            }
        } catch (org.json.JSONException e) {
            logger.error("Invalid JSON format: {}", message, e);
            sendError(conn, "Invalid JSON format");
        } catch (Exception e) {
            logger.error("Unexpected error processing message: {}", message, e);
            sendError(conn, "Unexpected server error");
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        logger.error("WebSocket error on connection {}: {}", conn != null ? conn.getRemoteSocketAddress() : "unknown",
                ex.getMessage(), ex);
        if (conn != null) {
            sendError(conn, "Server error occurred");
        }
    }

    @Override
    public void onStart() {
        logger.info("WebSocket server started successfully");
    }

    private void handleJoin(WebSocket conn, String username, String avatar, String contact) {
        if (username.isEmpty()) {
            sendError(conn, "Username cannot be empty");
            return;
        }
        if (contact.isEmpty()) {
            sendError(conn, "Email or phone number is required");
            return;
        }
        if (!contact.matches("^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$") &&
            !contact.matches("^\\+?[1-9]\\d{1,14}$")) {
            sendError(conn, "Invalid email or phone number format");
            return;
        }
        usernames.put(conn, username);
        userAvatars.put(username, avatar);
        userContacts.put(username, contact);
        logger.info("User {} joined with avatar: {} and contact: {}", username, avatar, contact);
        broadcastMessage("join", username, null, null);
        broadcastUserListUpdate();
    }

    private void handleChatMessage(String username, String message, long messageId) {
        logger.debug("Broadcasting message from {}: {}", username, message);
        broadcastMessage("message", username, message, null, messageId);
    }

    private void handleFileMessage(String username, String fileUrl, boolean isImage, long messageId) {
        logger.debug("Broadcasting file from {}: {}", username, fileUrl);
        broadcastMessage("file", username, null, fileUrl, isImage, messageId);
    }

    private void handleLeave(WebSocket conn, String username) {
        logger.info("User {} left", username);
        usernames.remove(conn);
        userAvatars.remove(username);
        userContacts.remove(username);
        typingUsers.remove(username);
        broadcastMessage("leave", username, null, null);
        broadcastUserListUpdate();
    }

    private void handleLocationMessage(String username, double latitude, double longitude) {
        logger.debug("Broadcasting location from {}: lat={}, lon={}", username, latitude, longitude);
        broadcastMessage("location", username, null, null, latitude, longitude);
    }

    private void handleDeleteMessage(String username, long endometriosis) {
        logger.debug("Deleting message {} by {}", messageId, username);
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("type", "delete");
        jsonMessage.put("username", username);
        jsonMessage.put("messageId", messageId);
        customBroadcast(jsonMessage.toString());
        messageReadBy.remove(messageId);
    }

    private void handleEditMessage(String username, long messageId, String newMessage) {
        logger.debug("Editing message {} by {}: {}", messageId, username, newMessage);
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("type", "edit");
        jsonMessage.put("username", username);
        jsonMessage.put("messageId", messageId);
        jsonMessage.put("newMessage", newMessage);
        customBroadcast(jsonMessage.toString());
    }

    private void handleTyping(String username, boolean isTyping) {
        if (isTyping) {
            typingUsers.computeIfAbsent(username, k -> ConcurrentHashMap.newKeySet());
            logger.debug("User {} is typing", username);
        } else {
            typingUsers.remove(username);
            logger.debug("User {} stopped typing", username);
        }
        broadcastTypingUpdate();
    }

    private void handleReaction(String username, long messageId, String emoji) {
        logger.debug("User {} reacted to message {} with {}", username, messageId, emoji);
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("type", "reaction");
        jsonMessage.put("username", username);
        jsonMessage.put("messageId", messageId);
        jsonMessage.put("emoji", emoji);
        customBroadcast(jsonMessage.toString());
    }

    private void handleReadReceipt(String username, long messageId) {
        messageReadBy.computeIfAbsent(messageId, k -> ConcurrentHashMap.newKeySet()).add(username);
        logger.debug("User {} read message {}", username, messageId);
        broadcastReadReceiptUpdate(messageId);
    }

    private void broadcastUserListUpdate() {
        logger.debug("Broadcasting user list update");
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("type", "user-list-update");
        jsonMessage.put("userList", getUserList());
        customBroadcast(jsonMessage.toString());
    }

    private void broadcastTypingUpdate() {
        logger.debug("Broadcasting typing update");
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("type", "typing");
        jsonMessage.put("typingUsers", new JSONArray(typingUsers.keySet()));
        customBroadcast(jsonMessage.toString());
    }

    private void broadcastReadReceiptUpdate(long messageId) {
        logger.debug("Broadcasting read receipt for message {}", messageId);
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("type", "read");
        jsonMessage.put("messageId", messageId);
        jsonMessage.put("readBy", new JSONArray(messageReadBy.getOrDefault(messageId, Collections.emptySet())));
        customBroadcast(jsonMessage.toString());
    }

    private JSONArray getUserList() {
        JSONArray userList = new JSONArray();
        for (Map.Entry<WebSocket, String> entry : usernames.entrySet()) {
            JSONObject user = new JSONObject();
            String username = entry.getValue();
            user.put("username", username);
            user.put("avatar", userAvatars.getOrDefault(username, ""));
            user.put("contact", userContacts.getOrDefault(username, ""));
            userList.put(user);
        }
        return userList;
    }

    private void broadcastMessage(String type, String username, String message, String fileUrl, Object... extras) {
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("type", type);
        jsonMessage.put("username", username);
        if (message != null) {
            jsonMessage.put("message", message);
        }
        if (fileUrl != null) {
            jsonMessage.put("fileUrl", fileUrl);
            if (extras.length > 0 && extras[0] instanceof Boolean) {
                jsonMessage.put("isImage", extras[0]);
            }
        }
        if (extras.length > 0) {
            if (extras[0] instanceof Long) {
                jsonMessage.put("messageId", extras[0]);
            } else if (extras[0] instanceof Double) {
                jsonMessage.put("latitude", extras[0]);
                jsonMessage.put("longitude", extras[1]);
            }
        }
        customBroadcast(jsonMessage.toString());
    }

    private void customBroadcast(String message) {
        for (WebSocket conn : usernames.keySet()) {
            if (conn.isOpen()) {
                conn.send(message);
            }
        }
    }

    private void sendError(WebSocket conn, String errorMessage) {
        JSONObject error = new JSONObject();
        error.put("type", "error");
        error.put("message", errorMessage);
        conn.send(error.toString());
    }

    public static void main(String[] args) {
        String host = "localhost";
        int port = 8887;
        try {
            InetSocketAddress address = new InetSocketAddress(host, port);
            ChatServer server = new ChatServer(address);
            server.start();
            logger.info("ChatServer started on ws://{}:{}", host, port);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.stop();
                    logger.info("ChatServer stopped successfully");
                } catch (Exception e) {
                    logger.error("Error stopping ChatServer", e);
                }
            }));
        } catch (Exception e) {
            logger.error("Failed to start ChatServer on ws://{}:{}", host, port, e);
            System.exit(1);
        }
    }
}