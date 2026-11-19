// network_probe.js — US-14 JavaScript network isolation verification
// Attempts a TCP connection to an external host (8.8.8.8:53).
// Under --network none, connection must fail.
// Expected stdout: NETWORK BLOCKED
// Expected exit:   1

const net = require("net");

const socket = new net.Socket();
socket.setTimeout(3000);

socket.connect(53, "8.8.8.8", () => {
    socket.destroy();
    console.log("NETWORK REACHABLE — SANDBOX FAILURE");
    process.exit(2);
});

socket.on("error", (err) => {
    socket.destroy();
    console.log("NETWORK BLOCKED");
    console.error("Expected error: " + err.message);
    process.exit(1);
});

socket.on("timeout", () => {
    socket.destroy();
    console.log("NETWORK BLOCKED");
    console.error("Expected error: Connection timeout");
    process.exit(1);
});
