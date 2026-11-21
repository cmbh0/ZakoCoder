package com.simplechat.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSH 模式下的通配符转换。
 *
 * `find_files` 的参数是双星号加斜杠这种写法，而 SSH 实现要在远端用
 * `grep -E` 过滤路径 —— 转换错一位，模型就会得到"没有这个文件"，
 * 然后开始凭印象改代码。
 */
class SshGlobTest {

    @Test
    fun `双星号加斜杠能匹配根目录的文件`() {
        val regex = Regex(globToRegex("**/*.py"))
        assertTrue(regex.matches("main.py"))
        assertTrue(regex.matches("src/main.py"))
        assertTrue(regex.matches("a/b/c/main.py"))
        assertFalse(regex.matches("main.pyc"))
        assertFalse(regex.matches("src/main.kt"))
    }

    @Test
    fun `单星号不跨目录`() {
        val regex = Regex(globToRegex("src/*.kt"))
        assertTrue(regex.matches("src/Main.kt"))
        assertFalse(regex.matches("src/ui/Main.kt"))
    }

    @Test
    fun `点号与括号按字面量处理`() {
        val regex = Regex(globToRegex("build(1)/a.txt"))
        assertTrue(regex.matches("build(1)/a.txt"))
        assertFalse(regex.matches("build1/a.txt"))
        assertFalse(regex.matches("buildX1/aXtxt"))
    }

    @Test
    fun `前导点斜杠会被去掉`() {
        val regex = Regex(globToRegex("./src/**/*.js"))
        assertTrue(regex.matches("src/a/b.js"))
    }
}
