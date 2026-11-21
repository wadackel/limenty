package dev.wadackel.limenty.core.transport

/**
 * Runs the embedded test server on a fixed port so a device or emulator can connect to it.
 *
 * The Android side of the transport cannot be proven by JVM tests: the security provider, the
 * algorithms it exposes and SSHJ's behaviour on ART are all runtime concerns. This gives the
 * instrumentation tests a real server to talk to without involving anyone's actual machine.
 */
object TestSshServerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.getOrNull(0)?.toIntOrNull() ?: 2222
        val server = FixedPortTestSshServer(port).start()
        println("limenty test ssh server listening on 0.0.0.0:${server.port} (user=tester)")
        Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
        Thread.currentThread().join()
    }
}
