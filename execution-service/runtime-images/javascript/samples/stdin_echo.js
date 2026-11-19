// stdin_echo.js — US-14 JavaScript stdin verification program
// Reads lines from stdin and writes them back to stdout (uppercased).
// Expected input:   hello sandbox
// Expected output:  HELLO SANDBOX
// Expected exit:    0

const readline = require("readline");

const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
    terminal: false
});

rl.on("line", (line) => {
    console.log(line.toUpperCase());
});
