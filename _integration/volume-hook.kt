        Thread({ AdbSwitch.keepOn(applicationContext) }, "adb-keep-on").start()
        val volumeApp = applicationContext
        com.andrerinas.openheadunit.gwm.GwmClimateBar.volumeKey = { up ->
            AdbSwitch.withAdb(volumeApp) {
                com.shilapi.xcertplay.adb.LocalAdb(com.shilapi.xcertplay.adb.AdbKeys.load(volumeApp)).use { adb ->
                    if (adb.connect(mayAsk = false) == com.shilapi.xcertplay.adb.LocalAdb.Access.READY)
                        adb.shell("input keyevent ${if (up) 24 else 25}", 3_000)
                }
            }
        }
