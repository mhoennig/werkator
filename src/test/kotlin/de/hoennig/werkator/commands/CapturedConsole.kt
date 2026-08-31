package de.hoennig.werkator.commands

import java.io.ByteArrayOutputStream
import java.io.PrintStream

data class CapturedConsole(
    val stdout: String,
    val stderr: String,
)

/** Captures `System.out` and `System.err` while [block] runs. */
fun captureConsole(block: () -> Unit): CapturedConsole {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val previousOut = System.out
    val previousErr = System.err
    System.setOut(PrintStream(out, true))
    System.setErr(PrintStream(err, true))
    try {
        block()
    } finally {
        System.setOut(previousOut)
        System.setErr(previousErr)
    }
    return CapturedConsole(out.toString(), err.toString())
}
