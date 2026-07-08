# Java-Redis: A Modular, RESP-Compliant In-Memory Key-Value Store

A high-performance, multi-threaded, in-memory key-value database built from scratch in Java SE. This database complies with the Redis Serialization Protocol (RESP2), supports key expiry (TTL) with dual cleanup strategies, and includes an Append-Only File (AOF) persistence layer for crash recovery.

---
##   Overall System Architecture

Before looking at the parser code, let's look at the lifecycle of a request in our server. 

### 📡 The Runtime Context Flow

```
                      +-----------------------------+
                      |         TCP Client          |
                      +-----------------------------+
                           │                   ▲
    1. Raw RESP Command    │                   │ 6. Serialized RESP Response
    (e.g., *2\r\n$4\r\n...)│                   │ (e.g., +PONG\r\n)
                           ▼                   │
                      +-----------------------------+
                      |      Network Socket         |
                      +-----------------------------+
                           │                   ▲
                           ▼                   │
            +------------------------------+   │
            |     BufferedInputStream      |   │
            +------------------------------+   │
                           │                   │
                           ▼                   │
            +------------------------------+   │
            |  RespParser.RespReader       |   │
            +------------------------------+   │
                           │                   │
                           ▼                   │
            +------------------------------+   │
            |  RespValue (Type.ARRAY)      |   │
            +------------------------------+   │
                           │                   │
                           ▼                   │
            +------------------------------+   │
            |       Database Engine        |   │
            +------------------------------+   │
                           │                   │
                           ▼                   │
            +------------------------------+   │
            |  RespValue (Type.SIMPLE_STR) ────┘
            +------------------------------+
```

### How Execution Flows at Runtime:
1. **Network Layer**: A client (like `redis-cli`) connects to our server on port `6379`. The Operating System's TCP stack establishes a connection, represented in Java as a `Socket`.
2. **Buffering**: The `Socket` gives us an `InputStream` (raw bytes). We wrap it in a `BufferedInputStream` to prevent making expensive system calls for every single byte read.
3. **Parsing**: The `RespReader` processes the stream byte-by-byte. It reads the prefix (`*`, `$`, `:`, etc.), determines the type, parses the payload, and produces a structured `RespValue` object.
4. **Routing**: The `RedisServer` extracts the command name and arguments from the `RespValue` array and routes them to the `Database` engine.
5. **Execution**: The `Database` runs the command against a thread-safe `ConcurrentHashMap` store and returns a response `RespValue` object (e.g., `+OK\r\n`).
6. **Serialization**: The response `RespValue` is serialized back to a raw byte array (`byte[]`) and written directly to the socket's `OutputStream`.

---
## 🚀 Features

*   **RESP2 Protocol Support**: Completely parses and serializes core RESP types:
    *   Simple Strings (`+OK\r\n`)
    *   Errors (`-ERR...\r\n`)
    *   Integers (`:10\r\n`)
    *   Bulk Strings (`$5\r\nhello\r\n`)
    *   Arrays (`*2\r\n...`)
*   **Multi-Threaded Server Loop**: Utilizes Java's socket handling and a `CachedThreadPool` to manage hundreds of concurrent client connections with minimal latency.
*   **Thread-Safe Engine**: Backed by `ConcurrentHashMap` to ensure high-throughput, lock-free concurrency.
*   **Key Expiry & TTL**:
    *   **Passive (Lazy) Expiry**: Removes expired keys on-the-fly during read requests to avoid serving stale data.
    *   **Active Expiry**: Runs a background daemon thread every 1 second to sweep and clean expired keys, preventing memory leaks.
*   **AOF (Append-Only File) Persistence**: Logs state-mutating commands to disk and replays them on startup to recover data.
*   **Docker Integration**: Easily testable using standard `redis-cli` tools.

---

## 📂 Project Architecture

The database is built using a clean, flat architecture with separation of concerns:

```
java-redis/
├── Main.java           # Entrypoint: bootstraps AOF, Database, and Server
├── RedisServer.java    # Networking layer: handles multi-threaded TCP sockets
├── RespParser.java     # Protocol layer: contains the RESP parser and serializer
├── Database.java       # Database engine: houses thread-safe store and command router
├── Aof.java            # Persistence layer: handles AOF logging and data recovery
├── run.bat             # Build script: compiles and runs the server on Windows
└── start.bat           # Connection script: launches redis-cli via Docker CLI on Windows
```

---

## 🛠️ Supported Commands

| Command | Arguments | Return Type | Description |
| :--- | :--- | :--- | :--- |
| **`PING`** | `[message]` | Simple String / Bulk String | Returns `PONG` or the optional message. |
| **`ECHO`** | `message` | Bulk String | Echoes back the input message. |
| **`SET`** | `key value [EX seconds] [PX ms]` | Simple String | Sets the key to hold the string value with optional expiry. |
| **`GET`** | `key` | Bulk String | Retrieves the value of the key, or `nil` if expired/not found. |
| **`EXISTS`** | `key` | Integer | Returns `1` if the key exists, otherwise `0`. |
| **`DEL`** | `key` | Integer | Deletes the key. Returns `1` if removed, `0` if not found. |
| **`EXPIRE`** | `key seconds` | Integer | Sets a timeout on the key in seconds. Returns `1` if set, `0` if key doesn't exist. |
| **`TTL`** | `key` | Integer | Returns remaining TTL in seconds. Returns `-1` if no TTL, `-2` if not found/expired. |

---

## ⚡ Quick Start

### 1. Compile and Run the Server
Use the included `run.bat` script to clean-compile the source files into a `bin/` output directory and start the server:

```cmd
run.bat
```

### 2. Connect and Test
Use the included `start.bat` script to connect with `redis-cli` using Docker:

```cmd
start.bat
```

Once connected, run commands:
```
host.docker.internal:6379> PING
PONG
host.docker.internal:6379> SET name "Antigravity" EX 10
OK
host.docker.internal:6379> TTL name
(integer) 8
host.docker.internal:6379> GET name
"Antigravity"
```

---

## 💾 Persistence & Recovery

When write operations occur, they are recorded to `appendonly.aof` in RESP format. If the server is stopped (`Ctrl + C`) and restarted:

1. `Main.java` detects `appendonly.aof`.
2. `Aof.java` reads the bytes and parses them into commands.
3. The database replays the commands internally (without logging them back to disk).
4. Normal client socket handling resumes with the restored state intact.
