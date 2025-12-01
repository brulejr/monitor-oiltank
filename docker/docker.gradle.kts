import org.gradle.api.tasks.Exec

// You can override this with: -PffmpegDownloadUrl=...
val ffmpegDownloadUrl: String = (project.findProperty("ffmpegDownloadUrl") as String?)
    ?: "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz"

// Where we unpack ffmpeg during the build
val ffmpegExtractDir = layout.buildDirectory.dir("ffmpeg").get().asFile

tasks.register<Exec>("downloadFfmpeg") {
    group = "docker"
    description = "Downloads a static ffmpeg build for Linux amd64 and extracts the binary"

    // Mark output so Gradle can cache/skip
    outputs.dir(ffmpegExtractDir)

    doFirst {
        ffmpegExtractDir.mkdirs()
    }

    // Requires curl + tar on the build host
    commandLine(
        "bash", "-c", """
            set -euo pipefail
            cd "${ffmpegExtractDir.absolutePath}"

            if [ ! -f ffmpeg ]; then
              echo "Downloading ffmpeg from $ffmpegDownloadUrl"
              curl -L "$ffmpegDownloadUrl" -o ffmpeg.tar.xz
              tar -xf ffmpeg.tar.xz --strip-components=1
              chmod +x ffmpeg || true
            else
              echo "ffmpeg already present in ${ffmpegExtractDir.absolutePath}, skipping download"
            fi
        """.trimIndent()
    )
}
