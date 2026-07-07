import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Database {
    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> expiries = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread t = new Thread(runnable, "Active-Expiry-Cleaner");
                t.setDaemon(true);
                return t;
            });

    public Database() {
        scheduler.scheduleAtFixedRate(this::cleanExpiredKeys, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Helper to passively check if a key has expired. Deletes the key and its expiry if the current
     * time is past the expiry time.
     */

    private boolean isExpired(String key) {
        Long expiryTime = expiries.get(key);
        if (expiryTime == null) {
            return false;
        }
        if (System.currentTimeMillis() > expiryTime) {
            store.remove(key);
            expiries.remove(key);
            return true;
        }
        return false;
    }

    /**
     * Active cleanup routine run by the background thread.
     */
    private void cleanExpiredKeys() {
        long now = System.currentTimeMillis();
        for (String key : expiries.keySet()) {
            Long expiryTime = expiries.get(key);
            if (expiryTime != null && now > expiryTime) {
                store.remove(key);
                expiries.remove(key);
            }
        }

    }

    /**
     * Central routing engine for commands.
     */
    public RespValue executeCommand(String commandName, List<RespValue> args) {
        switch (commandName.toUpperCase()) {
            case "PING":
                if (args.size() > 1) {
                    return new RespValue(Type.BULK_STRING, args.get(1).value);
                }
                return new RespValue(Type.SIMPLE_STRING, "PONG");
            case "ECHO":
                if (args.size() < 2) {
                    return new RespValue(Type.ERROR,
                            "ERR wrong number of argumnets for 'echo' command");
                }
                return new RespValue(Type.BULK_STRING, args.get(1).value);
            case "SET":
                if (args.size() < 3) {
                    return new RespValue(Type.ERROR,
                            "ERR wrong number of argumnets for 'GET' command");
                }
                String setKey = (String) args.get(1).value;
                String setValue = (String) args.get(2).value;

                Long expiryTimestamp = null;

                /* Parse optional parameters [EX seconds] or [PX milliseconds] */
                if (args.size() >= 5) {
                    String option = ((String) args.get(3).value).toUpperCase();
                    String valStr = (String) args.get(4).value;
                    try {
                        long duration = Long.parseLong(valStr);
                        if (option.equals("EX")) {
                            expiryTimestamp = System.currentTimeMillis() + (duration * 1000);
                        } else if (option.equals("PX")) {
                            expiryTimestamp = System.currentTimeMillis() + duration;
                        } else {
                            return new RespValue(Type.ERROR, "ERR syntax error in SET option");
                        }
                    } catch (NumberFormatException e) {
                        return new RespValue(Type.ERROR,
                                "ERR value is not an integer or out of range");
                    }
                }
                store.put(setKey, setValue);
                if (expiryTimestamp != null) {
                    expiries.put(setKey, expiryTimestamp);
                } else {
                    expiries.remove(setKey);
                }

                return new RespValue(Type.SIMPLE_STRING, "OK");

            case "GET":
                if (args.size() < 2) {
                    return new RespValue(Type.ERROR,
                            "ERR wrong number of argumnets for 'GET' command");
                }
                String getKey = (String) args.get(1).value;

                if (isExpired(getKey)) {
                    return new RespValue(Type.BULK_STRING, null);
                }

                String getValue = store.get(getKey);
                return new RespValue(Type.BULK_STRING, getValue);

            case "DEL":
                if (args.size() < 2) {
                    return new RespValue(Type.ERROR, "ERR wrong number of arguments for DEL");
                }

                String delKey = (String) args.get(1).value;

                isExpired(delKey);

                long deletedCount = store.remove(delKey) != null ? 1 : 0;
                expiries.remove(delKey);

                return new RespValue(Type.INTEGER, deletedCount);

            case "EXISTS":
                if (args.size() < 2) {
                    return new RespValue(Type.ERROR, "ERR wrong number of arguments for EXISTS");
                }

                String existsKey = (String) args.get(1).value;
                if (isExpired(existsKey)) {
                    return new RespValue(Type.INTEGER, 0L);
                }

                long count = store.containsKey(existsKey) ? 1 : 0;
                return new RespValue(Type.INTEGER, count);

            case "EXPIRE":
                if (args.size() < 3) {
                    return new RespValue(Type.ERROR,
                            "ERR wrong number of arguments for 'expire' command");
                }

                String expireKey = (String) args.get(1).value;
                String secondsStr = (String) args.get(2).value;

                // If key is already expired || doesn't exists
                if (isExpired(expireKey) || !store.containsKey(expireKey)) {
                    return new RespValue(Type.INTEGER, 0L);
                }


                try {
                    long seconds = Long.parseLong(secondsStr);
                    long absoluteExpiry = System.currentTimeMillis() + (seconds * 1000);

                    expiries.put(expireKey, absoluteExpiry);
                    return new RespValue(Type.INTEGER, 1L);
                } catch (NumberFormatException e) {
                    return new RespValue(Type.ERROR, "ERR value is not an integer or out of range");
                }

            case "TTL":
                if (args.size() < 2) {
                    return new RespValue(Type.ERROR,
                            "ERR wrong number of arguments for 'TTL' command");
                }
                String ttkey = (String) args.get(1).value;

                // if expired;
                if (isExpired(ttkey) || !store.containsKey(ttkey))
                    return new RespValue(Type.INTEGER, -2L);



                Long absoluteExpiry = expiries.get(ttkey);


                if (absoluteExpiry == null) {
                    return new RespValue(Type.INTEGER, -1L); // key exists but not expiryy
                }

                long remainingSeconds =
                        Math.max(0, (absoluteExpiry - System.currentTimeMillis()) / 1000);
                return new RespValue(Type.INTEGER, remainingSeconds);

            default:
                return new RespValue(Type.ERROR, "ERR unknown command '" + commandName + "'");
        }
    }
}
