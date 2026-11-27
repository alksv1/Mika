package com.wangzan101.mika.shell

import androidx.annotation.Keep
import com.wangzan101.mika.cap.phone.shell.ScreenInspect

/**
 * 只读读屏的入口:电脑端 CLI(`mika screen`)的命令里写的是这个全名,不随内部重构改名;实现在手机能力里。
 * `CLASSPATH=<base.apk> app_process /system/bin com.wangzan101.mika.shell.ScreenInspectMain`
 */
@Keep
object ScreenInspectMain {
    @JvmStatic
    fun main(args: Array<String>) = ScreenInspect.main(args)
}
