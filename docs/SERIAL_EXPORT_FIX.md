# 序号识别与导出修复（2.7.2）

整图 OCR 没认出足够序号时，放大左侧序号区域重试。两条以上数字指向同一起始号且最高票唯一时才采用；平票不再按导入页的默认范围猜号。

所有已导入照片都需要已识别或手动确认的起始号，包括前 3 页。点击页面状态可填写起始号，先预览首末号和行数，再确认；只更新编号，保留姓名、车牌及打钩。导出前逐页要求补齐未知编号，取消则返回核对，不再静默漏掉页面。

识别到的行数仍来自网格检测；本版本不修复纸张缺行、重复网格或跨行错位。预览的末号、行数若与原纸不一致，需要重新裁剪或拍摄。

## 验证

- JDK 17，Gradle `assembleDebug assembleDebugAndroidTest`。
- 纯 Java 回归：编译 `SerialNumberResolver.java` 与 `app/src/testSerial/java/com/parking/checkscanner/SerialNumberResolverTest.java`，运行同名测试主类，覆盖空票、单票、冲突、无效范围及 116 起始号等 7 例。
- Android 14 ARM64 模拟器：自定义 instrumentation runner `com.parking.checkscanner.SerialExportTest`，验证合成序号列 OCR、前 3 页及后续页的导出门槛、填写 116、预览确认、打钩保留、合并与实际 Excel 生成。
- 用户提供的截图仅作为本地私有 fixture（`app/src/androidTest/assets/serial-116.jpg`），不提交公开仓库。没有 fixture 时跳过此项；合成图测试仍执行。截图可见部分检出 32 行，原整图仅 1 票、放大重试 9 票，正确恢复 116。没有原始整张照片，不能据此断言整页行数准确率。
- 手动确认测试将第 2 条记录改为 117 号，星期二夜打钩；实际输出 XLSX 的 G123 为数值 1，模板中没有公式，此项不作为公式保留能力的验证。
- APK 使用与 2.7.1 相同证书，versionCode 39 / versionName 2.7.2。

运行 instrumentation（两包须使用同一证书）：

```
adb install -r app-debug.apk
adb install -r app-debug-androidTest.apk
adb shell am instrument -w com.parking.checkscanner.test/com.parking.checkscanner.SerialExportTest
```

本次未做真机摄像头拍摄验证。
