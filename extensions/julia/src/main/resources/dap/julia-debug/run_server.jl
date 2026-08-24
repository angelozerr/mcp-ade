using Sockets, DebugAdapter

server = listen(0)
port = getsockname(server)[2]
println("DAP server listening on port $port")
flush(stdout)

conn = accept(server)
close(server)

run(DebugAdapter.DebugSession(conn))
