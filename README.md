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
  - **相册一次多选导入**：底部【相册多选】一次最多可勾选 20 张表格照片（共 20 个页面），从当前聚焦页开始（纵览模式从第 1 页开始）按选择顺序依次填入后续页面并逐张识别，完成后回到纵览查看全部结果。
  - **多页按序号合并导出**：导出时第 1~3 页与第 4 页起导入过照片的页面按 OCR 识别出的序号合并成一份名单，同一人在多张照片上的打钩取并集；未识别出序号的照片不参与导出，导入时会提示重拍。

- ⚡ **智能灵敏度即时切换 (Adaptive Sensitivity)**
  - 预览区【灵敏度】标签点按即可在三档间循环切换：
    - `灵敏度：自适应` (1.0x, 标准推荐)
    - `灵敏度：增强` (1.25x, 专抓浅铅笔/淡墨笔迹)
    - `灵敏度：严格` (0.85x, 强抗重污渍/背面透印杂质)
  - 切换后 **50 毫秒内**全表重算刷新，无需重新拍照或导入。

- 🔤 **离线 OCR 花名册动态提取 (ML Kit Chinese OCR)**
  - 内置离线汉字与车牌识别引擎，自动从纸质表格中提取“姓名”与“车牌号”。
  - 动态关联匹配 115 人以上排班大表，无需手工录入花名册。

- 📊 **Excel 智能填报与导出 (Smart Excel Template Filler)**
  - **按图填 1 导出**：可直接从手机选取任意 `.xlsx` 考勤表（如微信收到的表格），系统自动按识别结果在对应人员和班次单元格填入数值 `1`。
  - **原样格式与公式保留**：完全保留原表格的字体、边框、背景色以及 `=SUM()` 等求和公式，填入的 `1` 会直接触发整表出勤小计与统计！
  - **一键分享调用**：选表填报后秒级生成新文件，直接调起系统分享至微信、QQ、WPS Office 或邮件。

- 🎓 **训练数据自动累计与端侧自优化 (Active Learning & Dataset Collection)**
  - **修正即沉淀**：日常使用中所做的每一次手动纠错（补勾或取消）以及整表导出，都会被自动裁剪为标准带标注样本切片并存入 `dataset_manifest.jsonl`。
  - **端侧在线自校准**：右上角菜单【训练集】->【⚡ 立即自校准算法】，手机本地即可根据您的历史真实笔迹对比度自动重定最优阈值，越用越聪明。
  - **一键打包导出 ZIP**：一键将累积的样本库与标注文件打包为 ZIP 压缩包，方便发送给算法团队进行深度神经网络微调。

- 🛡️ **100% 离线隐私安全与网络自愈**
  - 不依赖任何公网服务器或第三方云端 API，即使在无信号的地下车库或防空洞也能丝滑使用。
  - 网络自愈机制，后台检测静默防打扰，杜绝任何报错弹窗。

---

## 🏗️ 核心架构与模块

```
app/src/main/java/com/parking/checkscanner/
├── MainActivity.java           # 主界面控制器：图像采集、多卡片纵览、交互微调、灵敏度切换
├── CheckmarkDetector.java      # 核心视觉引擎：自适应网格追踪、积分图滤波、打钩连通域分析
├── TableOcrScanner.java        # 端侧 OCR 引擎：ML Kit 离线文字识别、花名册与车牌号对齐
├── ExcelTemplateFiller.java    # Excel 智能填报：基于 Apache POI 针对用户输入表写入数字 1 并保留公式
├── ExcelExporter.java          # Excel 导出器：标准排班考勤表生成与系统分享调用
├── TrainingDataCollector.java  # 训练数据沉淀引擎：样本切片采集、端侧自校准、ZIP 一键打包
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
