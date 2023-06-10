package io.github.irgaly.kfswatch.internal.platform

/**
 * platform File separator で path を比較できる Path クラス
 */
internal class PlatformPath(
    val originalPath: String
) {
    private val pathSeparator = when {
        (Platform.isJvmWindows ||
                Platform.isNodejsWindows ||
                Platform.isWindows) -> "\\"

        else -> "/"
    }

    val path = originalPath.replace("/", pathSeparator)

    override fun equals(other: Any?): Boolean {
        return (path == (other as? PlatformPath)?.path)
    }

    override fun hashCode(): Int {
        return path.hashCode()
    }

    override fun toString(): String {
        return "PlatformPath($originalPath)"
    }
}
