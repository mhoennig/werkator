package de.hoennig.gittally.git

import org.springframework.stereotype.Service
import java.nio.file.Path
import java.nio.file.Paths

@Service
class GitService {
    fun getTopLevel(workingDir: Path = Paths.get(".")): Path {
        val process =
            ProcessBuilder("git", "rev-parse", "--show-toplevel")
                .directory(workingDir.toFile())
                .start()
        if (process.waitFor() != 0) {
            throw RuntimeException("Not a git repository")
        }
        return Paths.get(
            process.inputStream
                .bufferedReader()
                .readText()
                .trim(),
        )
    }

    fun getOriginUrl(workingDir: Path = Paths.get(".")): String? {
        val process =
            ProcessBuilder("git", "remote", "get-url", "origin")
                .directory(workingDir.toFile())
                .start()
        if (process.waitFor() != 0) {
            return null
        }
        return process.inputStream
            .bufferedReader()
            .readText()
            .trim()
    }
}
