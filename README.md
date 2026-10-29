# CheckMarkScanner (打钩识别与 Excel 核对助手)

[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/Release-v2.5.2-brightgreen.svg)](https://github.com/Air-0000/CheckMarkScanner/releases)

**CheckMarkScanner** 是一款专为企事业单位、物业岗亭、巡更值班及排班考勤场景打造的 **全端侧离线纸质表格打钩识别与 Excel 智能对齐核对助手**。

软件支持对现场实拍的纸质考勤排班表进行毫秒级视觉打钩分析、离线 OCR 花名册提取，并能直接根据导入的考勤 Excel 表格将打钩转换为数字 `1` 填入原表格对应位置，实现“拍照即出考勤报表”，全程 **100% 离线脱网运行**，无任何隐私泄露风险。

---

## 🌟 核心特性

- 📸 **端侧离线视觉打钩检测 (On-Device Vision Detection)**
  - 基于积分图局部自适应二值化与拓扑连通域几何分析，精准识别各种手写勾（√）、连笔勾与单笔勾。
  - **宽容度自适应网格追踪引擎**：主动吸附单元格物理黑线，强抗手持拍摄梯形透视收窄、纸张微弯与倾斜，下半区依然分毫不差。
  - **智能勾尖保护与淡笔兼容**：双笔划联合对比度与底线下移容错，完美捕获浅色铅笔、细圆珠笔及触底勾尖。
  - **相册一次多选导入**：底部【相册多选】一次最多可勾选 20 张表格照片（共 20 个页面），从当前页开始（还没有图片时从第 1 页开始）按选择顺序依次填入后续页面并逐张识别，完成后跳到导入的第一页；顶部页签只列出已导入的页，另有「＋ 新页」用于逐张拍照往后加页。
  - **对照照片点格核对**：照片上用黄框标出识别到的打钩，漏识别的格子点一下补勾、多识别的点一下取消；页签下方显示本页打钩数，序号未识别的页会以黄色标签提醒重拍。
  - **多页按序号合并导出**：导出时第 1~3 页与第 4 页起导入过照片的页面按 OCR 识别出的序号合并成一份名单，同一人在多张照片上的打钩取并集；未识别出序号的照片不参与导出，导入时会提示重拍。

- 🧾 **手写《废料登记表》识别与导出**
  - 标题栏右上角「废料登记」进入：拍照或相册多选导入手写废料登记表，按表格线逐格识别 日期 / 回仓部门 / 物品名称 / 重量 / 回仓地点 / 跟进人员 / 跟进时间 / 保安人员。
  - 手写内容用端侧 PaddleOCR 识别模型 (PP-OCRv4 mobile + PP-OCRv6 small 两个合用，ONNX Runtime) 读取，部门、物品、地点、人员按**固定类别清单**就近纠正；没认准的格子在照片和列表里标黄，点一行即可对照该行截图修改。
  - 核对时改过的值会记进清单，并记住「识别原文 → 正确值」，同一笔迹下次自动对上；清单也可在右上角菜单里整列编辑。
  - 「导出 Excel」生成《废料登记表》(与纸质表同列、末行重量合计) 及按物品 / 部门的重量汇总表，直接分享。

- 🔤 **离线 OCR 花名册动态提取 (ML Kit Chinese OCR)**
  - 内置离线汉字与车牌识别引擎，自动从纸质表格中提取“姓名”与“车牌号”。
  - 动态关联匹配 115 人以上排班大表，无需手工录入花名册。

- 📊 **Excel 智能填报与导出 (Smart Excel Template Filler)**
  - **按图填 1 导出**：可直接从手机选取任意 `.xlsx` 考勤表（如微信收到的表格），系统自动按识别结果在对应人员和班次单元格填入数值 `1`。
  - **原样格式与公式保留**：完全保留原表格的字体、边框、背景色以及 `=SUM()` 等求和公式，填入的 `1` 会直接触发整表出勤小计与统计！
  - **一键分享调用**：选表填报后秒级生成新文件，直接调起系统分享至微信、QQ、WPS Office 或邮件。

- 🛡️ **100% 离线隐私安全与网络自愈**
  - 不依赖任何公网服务器或第三方云端 API，即使在无信号的地下车库或防空洞也能丝滑使用。
  - 网络自愈机制，后台检测静默防打扰，杜绝任何报错弹窗。

---

## 🏗️ 核心架构与模块

```
app/src/main/java/com/parking/checkscanner/
├── MainActivity.java           # 主界面控制器：拍照 / 相册导入、页签切换、点格补勾 / 取消、导出
├── CheckmarkDetector.java      # 核心视觉引擎：自适应网格追踪、积分图滤波、打钩连通域分析
├── TableOcrScanner.java        # 端侧 OCR 引擎：ML Kit 离线文字识别、花名册与车牌号对齐
├── WasteActivity.java          # 废料登记表界面：导入识别、对照照片逐行修改、导出
├── WasteSheetScanner.java      # 废料表识别：表格线检测、逐格手写识别、按列规整与标黄
├── PaddleTextRecognizer.java   # 端侧手写识别：PaddleOCR 识别模型 (assets/ppocr_rec.onnx、ppocr_v6s_rec.onnx)
├── WasteCategories.java        # 废料表固定类别清单、形近字纠正与别名学习
├── WasteExcelExporter.java     # 废料登记表 .xlsx 生成 (登记表 + 汇总)
├── ExcelTemplateFiller.java    # Excel 智能填报：基于 Apache POI 针对用户输入表写入数字 1 并保留公式
├── ExcelExporter.java          # Excel 导出器：标准排班考勤表生成与系统分享调用
├── TrainingDataCollector.java  # 训练数据工具：现仅用于读取旧版自校准过的打钩对比度阈值
├── AppUpdateManager.java       # 应用内更新自愈管理器：静默检测、离线状态自愈
├── ShiftConstants.java         # 班次与标准花名册常量定义 (周一白 ~ 周日夜 14 班次)
├── ParkingRecord.java          # 人员排班记录数据模型
└── ImageUtils.java             # 图像色彩空间归一化与 EXIF 旋转矫正
```

---

## 🛠️ 构建与编译说明

### 环境要求
- **JDK**：Java 17 或更高版本
- **Android SDK**：API Level 34 (Android 14)
- **Min SDK**：API Level 26 (Android 8.0)
- **Gradle**：8.7 (已包含 `gradlew` wrapper)

### 本地构建命令

```bash
# 克隆仓库
git clone https://github.com/Air-0000/CheckMarkScanner.git
cd CheckMarkScanner

# 编译 Debug APK
./gradlew assembleDebug

# 生成的 APK 安装包位于：
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 开源许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
