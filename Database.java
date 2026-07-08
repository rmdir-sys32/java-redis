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

    private Aof aof;

    public Database() {
        scheduler.scheduleAtFixedRate(this::cleanExpiredKeys, 1, 1, TimeUnit.SECONDS);
    }

    // Set the AOF manager after database initialisation
    public void setAof(Aof aof) {
        this.aof = aof;
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
     * Helper to identify if a command alters the state of the database
     */
    private boolean isWriteCommand(String commandName) {
        String cmd = commandName.toUpperCase();
        return cmd.equals("SET") || cmd.equals("DEL") || cmd.equals("EXPIRE");
    }

    /**
     * Public entrypoint for executing client commands (writetoAOF = true)
     */
    public RespValue executeCommand(String commandName, List<RespValue> args) {
        return executeCommandInternal(commandName, args, true);
    }

    /**
     * Central routing engine for commands.
     */
    public RespValue executeCommandInternal(String commandName, List<RespValue> args,
            boolean writeToAof) {
        String upperCommand = commandName.toUpperCase();

        RespValue result = null;

        // Process command logic
        switch (upperCommand) {
            case "PING":
                if (args.size() > 1) {
                    return new RespValue(Type.ERROR, "No arguments allowed for PING");
                } else {
                    result = new RespValue(Type.SIMPLE_STRING, "PONG");
                }
                break;

            case "ECHO":
                if (args.size() < 2) {
                    result = new RespValue(Type.ERROR,
                            "ERR wrong number of argumnets for 'echo' command");
                } else {
                    result = new RespValue(Type.BULK_STRING, args.get(1).value);
                }
                break;

            case "SET":
                // 1. Initial check: Ensure we have at least Key and Value
                if (args.size() < 3) {
                    result = new RespValue(Type.ERROR,
                            "ERR wrong number of arguments for 'set' command");
                    break;
                }

                String setKey = (String) args.get(1).value;
                String setValue = (String) args.get(2).value;

                Long expiryTimestamp = null;
                boolean hasError = false;

                // 2. Loop to parse options starting at index 3
                int i = 3;
                while (i < args.size()) {
                    String option = ((String) args.get(i).value).toUpperCase();

                    if (option.equals("EX") || option.equals("PX")) {
                        // Check if both EX and PX (or duplicates) are entered
                        if (expiryTimestamp != null) {
                            result = new RespValue(Type.ERROR, "ERR syntax error");
                            hasError = true;
                            break;
                        }

                        // Checking if the value following the option exists
                        if (i + 1 >= args.size()) {
                            result = new RespValue(Type.ERROR, "ERR syntax error");
                            hasError = true;
                            break;
                        }

                        String valStr = (String) args.get(i + 1).value;
                        try {
                            long duration = Long.parseLong(valStr);
                            if (option.equals("EX")) {
                                expiryTimestamp = System.currentTimeMillis() + (duration * 1000);
                            } else if (option.equals("PX")) {
                                expiryTimestamp = System.currentTimeMillis() + duration;
                            }
                        } catch (NumberFormatException e) {
                            result = new RespValue(Type.ERROR,
                                    "ERR value is not an integer or out of range");
                            hasError = true;
                            break;
                        }
                        i += 2; // Move past option and its value
                    } else {
                        // Unknown option
                        result = new RespValue(Type.ERROR, "ERR syntax error");
                        hasError = true;
                        break;
                    }
                }

                // 3. Write to store only if no syntax/parsing errors occurred
                if (!hasError) {
                    store.put(setKey, setValue);
                    if (expiryTimestamp != null) {
                        expiries.put(setKey, expiryTimestamp);
                    } else {
                        expiries.remove(setKey);
                    }
                    result = new RespValue(Type.SIMPLE_STRING, "OK");
                }
                break;


            case "GET":
                if (args.size() != 2) {
                    result = new RespValue(Type.ERROR,
                            "ERR wrong number of arguments for 'GET' command");
                } else {
                    String getKey = (String) args.get(1).value;
                    if (isExpired(getKey)) {
                        result = new RespValue(Type.BULK_STRING, null);
                    } else {
                        String getValue = store.get(getKey);
                        result = new RespValue(Type.BULK_STRING, getValue);
                    }
                }
                break;

            case "DEL":
                if (args.size() < 2) {
                    result = new RespValue(Type.ERROR, "ERR wrong number of arguments for DEL");
                } else {
                    String delKey = (String) args.get(1).value;
                    isExpired(delKey);
                    long deletedCount = store.remove(delKey) != null ? 1 : 0;
                    expiries.remove(delKey);
                    result = new RespValue(Type.INTEGER, deletedCount);
                }
                break;

            case "EXISTS":
                if (args.size() < 2) {
                    result = new RespValue(Type.ERROR, "ERR wrong number of arguments for EXISTS");
                } else {
                    String existsKey = (String) args.get(1).value;
                    if (isExpired(existsKey)) {
                        result = new RespValue(Type.INTEGER, 0L);
                    } else {
                        long count = store.containsKey(existsKey) ? 1 : 0;
                        result = new RespValue(Type.INTEGER, count);
                    }
                }
                break;

            case "EXPIRE":
                if (args.size() < 3) {
                    result = new RespValue(Type.ERROR,
                            "ERR wrong number of arguments for 'expire' command");
                } else {
                    String expireKey = (String) args.get(1).value;
                    String secondsStr = (String) args.get(2).value;

                    // If key is already expired || doesn't exist
                    if (isExpired(expireKey) || !store.containsKey(expireKey)) {
                        result = new RespValue(Type.INTEGER, 0L);
                    } else {
                        try {
                            long seconds = Long.parseLong(secondsStr);
                            long absoluteExpiry = System.currentTimeMillis() + (seconds * 1000);
                            expiries.put(expireKey, absoluteExpiry);
                            result = new RespValue(Type.INTEGER, 1L);
                        } catch (NumberFormatException e) {
                            result = new RespValue(Type.ERROR,
                                    "ERR value is not an integer or out of range");
                        }
                    }
                }
                break;

            case "TTL":
                if (args.size() < 2) {
                    result = new RespValue(Type.ERROR,
                            "ERR wrong number of arguments for 'TTL' command");
                } else {
                    String ttkey = (String) args.get(1).value;
                    if (isExpired(ttkey) || !store.containsKey(ttkey)) {
                        result = new RespValue(Type.INTEGER, -2L);
                    } else {
                        Long absoluteExpiry = expiries.get(ttkey);
                        if (absoluteExpiry == null) {
                            result = new RespValue(Type.INTEGER, -1L); // key exists but not expiry
                        } else {
                            long remainingSeconds = Math.max(0,
                                    (absoluteExpiry - System.currentTimeMillis()) / 1000);
                            result = new RespValue(Type.INTEGER, remainingSeconds);
                        }
                    }
                }
                break;

            default:
                result = new RespValue(Type.ERROR, "ERR unknown command '" + commandName + "'");
                break;
        }

        /**
         * Logging if command succeeded, is a write command, and AOF logging is active
         */
        if (writeToAof && aof != null &&

                isWriteCommand(upperCommand) && result != null && result.type != Type.ERROR) {
            aof.write(new RespValue(Type.ARRAY, args));
        }

        return result;
    }
}
