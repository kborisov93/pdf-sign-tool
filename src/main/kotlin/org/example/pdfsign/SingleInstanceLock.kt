package org.example.pdfsign

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** An exclusive lock on a file that the operating system releases when the process ends. */
class SingleInstanceLock private constructor(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {

    override fun close() {
        lock.release()
        channel.close()
    }

    companion object {
        /** Returns null when another process already holds the lock. */
        fun tryAcquire(file: Path): SingleInstanceLock? {
            Files.createDirectories(file.parent)
            val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                return null
            }
            return SingleInstanceLock(channel, lock)
        }
    }
}
