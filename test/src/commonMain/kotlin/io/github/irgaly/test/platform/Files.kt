package io.github.irgaly.test.platform

expect class Files {
    companion object {
        suspend fun createTemporaryDirectory(): String
        fun createTemporaryDirectorySync(): String
        suspend fun createDirectory(path: String): Boolean
        suspend fun writeFile(path: String, text: String): Boolean

        /**
         * ファイル名を変更または移動する
         *
         * * 移動先が存在するとき
         *     * ファイル: 上書きされる
         *     * ディレクトリ: 空のディレクトリなら上書きされる
         */
        suspend fun move(source: String, destination: String): Boolean
        suspend fun deleteRecursively(path: String): Boolean
    }
}
