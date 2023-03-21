package io.github.irgaly.test

import io.github.irgaly.test.platform.Platform
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * kotest JS ではネストしたテストや DescribeSpec は使えない
 * DescribeSpec の書き方でフラットなテストとなるようにブリッジする
 * テストは it の中でのみ実行可能
 * すべての it の実行順は保証しない
 */
open class DescribeFunSpec(
    body: (DescribeFunSpec.() -> Unit) = {}
) : FunSpec() {
    // kotest が DescribeFunSpec を実行しようとするが、
    // zero arg constructor がないとエラーになるので
    // dummy constructor
    @Suppress("unused")
    constructor(): this(body = {})

    init {
        body()
    }

    override fun test(name: String, test: suspend TestScope.() -> Unit) {
        if (Platform.isJs) {
            super.test(name) {
                withContext(Dispatchers.Main) {
                    // nodejs + turbine で Coroutine をテストするとき、
                    // Flow の subscription のタイミングが遅れる問題を発生させないために
                    // Dispatchers.Main を使う
                    test()
                }
            }
        } else super.test(name, test)
    }

    fun it(name: String, test: suspend TestScope.() -> Unit) {
        test(name, test)
    }

    fun xit(name: String, test: suspend TestScope.() -> Unit) {
        xtest(name, test)
    }

    fun context(name: String, test: ContextScope.() -> Unit) {
        test(ContextScope(name))
    }

    @Suppress("UNUSED_PARAMETER")
    fun xcontext(name: String, test: ContextScope.() -> Unit) {
        xtest(name)
    }

    fun describe(name: String, test: DescribeScope.() -> Unit) {
        test(DescribeScope(name))
    }

    @Suppress("UNUSED_PARAMETER")
    fun xdescribe(name: String, test: DescribeScope.() -> Unit) {
        xtest(name)
    }

    inner class ContextScope(val contextName: String) {
        fun it(name: String, test: suspend TestScope.() -> Unit) {
            test("$contextName: $name", test)
        }

        fun xit(name: String, test: suspend TestScope.() -> Unit) {
            xtest("$contextName: $name", test)
        }
    }

    inner class DescribeScope(val describeName: String) {
        fun it(name: String, test: suspend TestScope.() -> Unit) {
            test("$describeName: $name", test)
        }

        fun xit(name: String, test: suspend TestScope.() -> Unit) {
            xtest("$describeName: $name", test)
        }

        fun context(name: String, test: ContextScope.() -> Unit) {
            test(ContextScope("$describeName: $name"))
        }

        @Suppress("UNUSED_PARAMETER")
        fun xcontext(name: String, test: ContextScope.() -> Unit) {
            xtest("$describeName: $name")
        }
    }
}
